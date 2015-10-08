package org.apache.spark.rdd.cl

import scala.reflect.ClassTag
import scala.reflect._
import scala.reflect.runtime.universe._

import java.net._
import java.util.LinkedList
import java.util.Map
import java.util.HashMap

import org.apache.spark.{Partition, TaskContext}
import org.apache.spark.rdd._
import org.apache.spark.broadcast.Broadcast

import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.mllib.linalg.SparseVector

import com.amd.aparapi.internal.model.ClassModel
import com.amd.aparapi.internal.model.Tuple2ClassModel
import com.amd.aparapi.internal.model.DenseVectorClassModel
import com.amd.aparapi.internal.model.SparseVectorClassModel
import com.amd.aparapi.internal.model.HardCodedClassModels
import com.amd.aparapi.internal.model.HardCodedClassModels.ShouldNotCallMatcher
import com.amd.aparapi.internal.model.Entrypoint
import com.amd.aparapi.internal.writer.KernelWriter
import com.amd.aparapi.internal.writer.KernelWriter.WriterAndKernel
import com.amd.aparapi.internal.writer.BlockWriter
import com.amd.aparapi.internal.writer.ScalaArrayParameter
import com.amd.aparapi.internal.writer.ScalaParameter.DIRECTION

class KernelDevicePair(val kernel : String, val dev_ctx : Long) {

  override def equals(obj : Any) : Boolean = {
    if (obj.isInstanceOf[KernelDevicePair]) {
      val other : KernelDevicePair = obj.asInstanceOf[KernelDevicePair]
      kernel.equals(other.kernel) && dev_ctx == other.dev_ctx
    } else {
      false
    }
  }

  override def hashCode() : Int = {
    dev_ctx.toInt
  }
}

/*
 * Shared by all threads within a single node across a whole job, multiple tasks
 * of multiple types from multiple threads may share the data stored here.
 *
 * Initialized single-threaded.
 *
 * I believe this might actually be run single-threaded in the driver, then
 * captured in the closure handed to the workers? Having data in here might
 * drastically increase the size of the data transmitted.
 */
object CLMappedRDDStorage {
  val nbuffers : Int = 2

  val N_str = System.getProperty("swat.input_chunking")
  val N = if (N_str != null) N_str.toInt else 50000

  val cl_local_size_str = System.getProperty("swat.cl_local_size")
  val cl_local_size = if (cl_local_size_str != null) cl_local_size_str.toInt else 128

  val spark_cores_str = System.getenv("SPARK_WORKER_CORES")
  assert(spark_cores_str != null)
  val spark_cores = spark_cores_str.toInt

  val swatContextCache : Array[java.util.HashMap[KernelDevicePair, Long]] =
        new Array[java.util.HashMap[KernelDevicePair, Long]](spark_cores)
  val inputBufferCache : Array[java.util.HashMap[String, InputBufferWrapper[_]]] =
      new Array[java.util.HashMap[String, InputBufferWrapper[_]]](spark_cores)
  val outputBufferCache : Array[java.util.HashMap[String, OutputBufferWrapper[_]]] =
      new Array[java.util.HashMap[String, OutputBufferWrapper[_]]](spark_cores)
  for (i <- 0 until spark_cores) {
    swatContextCache(i) = new java.util.HashMap[KernelDevicePair, Long]()
    inputBufferCache(i) = new java.util.HashMap[String, InputBufferWrapper[_]]()
    outputBufferCache(i) = new java.util.HashMap[String, OutputBufferWrapper[_]]()
  }
}

/*
 * A new CLMappedRDD object is created for each partition/task being processed,
 * lifetime and accessibility of items inside an instance of these is limited to
 * one thread and one task running on that thread.
 */
class CLMappedRDD[U: ClassTag, T: ClassTag](prev: RDD[T], f: T => U)
    extends RDD[U](prev) {
  val heapSize = 64 * 1024 * 1024
  val heapsPerDevice = CLMappedRDDStorage.spark_cores
  var entryPoint : Entrypoint = null
  var openCL : String = null

  override val partitioner = firstParent[T].partitioner

  override def getPartitions: Array[Partition] = firstParent[T].partitions

  override def compute(split: Partition, context: TaskContext) : Iterator[U] = {
    System.setProperty("com.amd.aparapi.enable.NEW", "true");
    System.setProperty("com.amd.aparapi.enable.ATHROW", "true");

    var inputBuffer : InputBufferWrapper[T] = null
    var nativeOutputBuffer : OutputBufferWrapper[U] = null
    var outputBuffer : Option[OutputBufferWrapper[U]] = None

/*
    if (threadId % 3 == 0) {
      // Every 2 threads runs on the JVM
      return new Iterator[U] {
        val nested = firstParent[T].iterator(split, context)

        def next() : U = {
          f(nested.next)
        }

        def hasNext() : Boolean = {
          nested.hasNext
        }
      }
    }
*/

    val threadId : Int = RuntimeUtil.getThreadID()

    System.err.println("Thread=" + threadId + " N=" + CLMappedRDDStorage.N +
            ", cl_local_size=" + CLMappedRDDStorage.cl_local_size +
            ", spark_cores=" + CLMappedRDDStorage.spark_cores)
    val nested = firstParent[T].iterator(split, context)

    if (CLMappedRDDStorage.inputBufferCache(threadId).containsKey(
                f.getClass.getName)) {
      assert(inputBuffer == null)
      assert(nativeOutputBuffer == null)

      inputBuffer = CLMappedRDDStorage.inputBufferCache(threadId).get(
              f.getClass.getName).asInstanceOf[InputBufferWrapper[T]]
      nativeOutputBuffer = CLMappedRDDStorage.outputBufferCache(threadId).get(
              f.getClass.getName).asInstanceOf[OutputBufferWrapper[U]]
    }

    val classModel : ClassModel = ClassModel.createClassModel(f.getClass, null,
        new ShouldNotCallMatcher())
    val method = classModel.getPrimitiveApplyMethod
    val descriptor : String = method.getDescriptor

    // 1 argument expected for maps
    val params : LinkedList[ScalaArrayParameter] =
        CodeGenUtil.getParamObjsFromMethodDescriptor(descriptor, 1)
    params.add(CodeGenUtil.getReturnObjsFromMethodDescriptor(descriptor))

    var totalNLoaded = 0
    val overallStart = System.currentTimeMillis

    val partitionDeviceHint : Int = OpenCLBridge.getDeviceHintFor(
            firstParent[T].id, split.index, totalNLoaded, 0)

   val deviceInitStart = System.currentTimeMillis // PROFILE
    val device_index = OpenCLBridge.getDeviceToUse(partitionDeviceHint,
            threadId, heapsPerDevice, heapSize)
   System.err.println("Thread " + threadId + " selected device " + device_index) // PROFILE
    val dev_ctx : Long = OpenCLBridge.getActualDeviceContext(device_index,
            heapsPerDevice, heapSize)
    val devicePointerSize = OpenCLBridge.getDevicePointerSizeInBytes(dev_ctx)
   RuntimeUtil.profPrint("DeviceInit", deviceInitStart, threadId) // PROFILE

   val firstSample : T = nested.next
   var firstBufferOp : Boolean = true
   var sampleOutput : java.lang.Object = None

   val initializing : Boolean = (entryPoint == null)

   if (initializing) {
     val initStart = System.currentTimeMillis // PROFILE
     sampleOutput = f(firstSample).asInstanceOf[java.lang.Object]
     val entrypointAndKernel : Tuple2[Entrypoint, String] =
         RuntimeUtil.getEntrypointAndKernel[T, U](firstSample, sampleOutput,
         params, f, classModel, descriptor, dev_ctx, threadId)
     entryPoint = entrypointAndKernel._1
     openCL = entrypointAndKernel._2

     if (inputBuffer == null) {
       inputBuffer = RuntimeUtil.getInputBufferFor(firstSample, CLMappedRDDStorage.N,
               DenseVectorInputBufferWrapperConfig.tiling,
               SparseVectorInputBufferWrapperConfig.tiling, entryPoint, false)
       CLMappedRDDStorage.inputBufferCache(threadId).put(f.getClass.getName,
               inputBuffer)
       nativeOutputBuffer = OpenCLBridgeWrapper.getOutputBufferFor[U](
               sampleOutput.asInstanceOf[U], CLMappedRDDStorage.N,
               entryPoint, devicePointerSize, heapSize)
       CLMappedRDDStorage.outputBufferCache(threadId).put(f.getClass.getName,
               nativeOutputBuffer)
     }

     RuntimeUtil.profPrint("Initialization", initStart, threadId) // PROFILE
   }
   inputBuffer.ready = false

   val ctxCreateStart = System.currentTimeMillis // PROFILE
   val kernelDeviceKey : KernelDevicePair = new KernelDevicePair(
           f.getClass.getName, dev_ctx)
   if (!CLMappedRDDStorage.swatContextCache(threadId).containsKey(kernelDeviceKey)) {
     val ctx : Long = OpenCLBridge.createSwatContext(
               f.getClass.getName, openCL, dev_ctx, threadId,
               entryPoint.requiresDoublePragma,
               entryPoint.requiresHeap, CLMappedRDDStorage.N)
     CLMappedRDDStorage.swatContextCache(threadId).put(kernelDeviceKey, ctx)
   }
   val ctx : Long = CLMappedRDDStorage.swatContextCache(threadId).get(
           kernelDeviceKey)

   val outArgNum : Int = inputBuffer.countArgumentsUsed
   var heapArgStart : Int = -1
   var lastArgIndex : Int = -1
   var heapTopArgNum : Int = -1
   val heapTopBuffer : Array[Int] = new Array[Int](1)
   try {
     var argnum = outArgNum
     argnum += OpenCLBridgeWrapper.setUnitializedArrayArg[U](ctx,
         dev_ctx, outArgNum, CLMappedRDDStorage.N, classTag[U].runtimeClass,
         entryPoint, sampleOutput.asInstanceOf[U], true)
     
     val iter = entryPoint.getReferencedClassModelFields.iterator
     while (iter.hasNext) {
       val field = iter.next
       val isBroadcast = entryPoint.isBroadcastField(field)
       argnum += OpenCLBridge.setArgByNameAndType(ctx, dev_ctx, argnum, f,
           field.getName, field.getDescriptor, entryPoint, isBroadcast)
     }

     if (entryPoint.requiresHeap) {
       heapArgStart = argnum
       heapTopArgNum = heapArgStart + 1
       if (!OpenCLBridge.setArgUnitialized(ctx, dev_ctx, heapTopArgNum + 2,
               CLMappedRDDStorage.N * 4, true)) {
         throw new OpenCLOutOfMemoryException();
       }
       lastArgIndex = heapArgStart + 4
     } else {
       lastArgIndex = argnum
     }
   } catch {
     case oomExc : OpenCLOutOfMemoryException => {
       System.err.println("SWAT PROF " + threadId + // PROFILE
               " OOM during initialization, using LambdaOutputBuffer") // PROFILE
       OpenCLBridge.cleanupArguments(ctx)

       return new Iterator[U] {
         val nested = firstParent[T].iterator(split, context)
         override def next() : U = { f(nested.next) }
         override def hasNext() : Boolean = { nested.hasNext }
       }
     }
   }
   OpenCLBridge.setupArguments(ctx); // Flush out the kernel arguments that never change

    val iter = new Iterator[U] {

     var nLoaded : Int = -1
     var noMoreInputBuffering = false
     var inputCacheSuccess : Int = -1
     var inputCacheId : CLCacheID = NoCache
     val readerRunner = new Runnable() {
       override def run() {
         var done : Boolean = false
         while (!done) {
           val ioStart = System.currentTimeMillis // PROFILE
           inputBuffer.reset

           val myOffset : Int = totalNLoaded
           inputCacheId = if (firstParent[T].getStorageLevel.useMemory)
               new CLCacheID(firstParent[T].id, split.index, myOffset, 0)
               else NoCache

           nLoaded = -1
           inputCacheSuccess = if (inputCacheId == NoCache) -1 else
             inputBuffer.tryCache(inputCacheId, ctx, dev_ctx, entryPoint, false)

           if (inputCacheSuccess != -1) {
             nLoaded = OpenCLBridge.fetchNLoaded(inputCacheId.rdd, inputCacheId.partition,
               inputCacheId.offset)
             /*
              * Drop the rest of this buffer from the input stream if cached,
              * accounting for the fact that some items may already have been
              * buffered from it, either in the inputBuffer (due to an overrun) or
              * because of firstSample.
              */
             if (firstBufferOp) {
               nested.drop(nLoaded - 1 - inputBuffer.nBuffered)
             } else {
               nested.drop(nLoaded - inputBuffer.nBuffered)
             }
           } else {
             if (firstBufferOp) {
               inputBuffer.append(firstSample)
             }

             inputBuffer.aggregateFrom(nested)
             inputBuffer.flush

             nLoaded = inputBuffer.nBuffered
             if (inputCacheId != NoCache) {
               OpenCLBridge.storeNLoaded(inputCacheId.rdd, inputCacheId.partition,
                 inputCacheId.offset, nLoaded)
             }
           }
           firstBufferOp = false
           totalNLoaded += nLoaded

           RuntimeUtil.profPrint("Input-I/O", ioStart, threadId) // PROFILE
           System.err.println("SWAT PROF " + threadId + " Loaded " + nLoaded) // PROFILE

           done = !nested.hasNext
           inputBuffer.synchronized {
             inputBuffer.ready = true
             inputBuffer.notify
             while (inputBuffer.ready) {
               inputBuffer.wait
             }
           }
         }
       }
     }
     var readerThread : Thread = new Thread(readerRunner)
     readerThread.start

     RuntimeUtil.profPrint("ContextCreation", ctxCreateStart, threadId) // PROFILE

     private def writeInputBufferToDevice(inputBuffer : InputBufferWrapper[T]) :
         Boolean = {
       try {
         val writeStart = System.currentTimeMillis // PROFILE
         inputBuffer.copyToDevice(0, ctx, dev_ctx, inputCacheId, false)
         RuntimeUtil.profPrint("Write", writeStart, threadId) // PROFILE
       } catch {
         case oomExc : OpenCLOutOfMemoryException => {
           System.err.println("SWAT PROF " + threadId + // PROFILE
                   " OOM during input allocation, using LambdaOutputBuffer") // PROFILE
           return true
         }
       }
       return false
     }

     def next() : U = {
       if (outputBuffer.isEmpty || !outputBuffer.get.hasNext) {
         nativeOutputBuffer.reset

         inputBuffer.synchronized {
           while (!inputBuffer.ready) {
             inputBuffer.wait
           }
         }

         val oom : Boolean = if (inputCacheSuccess == -1)
                writeInputBufferToDevice(inputBuffer) else false
         noMoreInputBuffering = !nested.hasNext

         if (oom) {
           // Make sure any persistent allocations are freed up and removed
           OpenCLBridge.cleanupArguments(ctx);
           outputBuffer = Some(new LambdaOutputBuffer[T, U](f, inputBuffer))
         } else {
           val thisNLoaded = nLoaded

           var ntries : Int = 0 // PROFILE
           var complete : Boolean = true

           OpenCLBridge.setIntArg(ctx, lastArgIndex, thisNLoaded)
           OpenCLBridge.setupArguments(ctx)
           OpenCLBridge.waitForPendingEvents(ctx)

           inputBuffer.synchronized {
             inputBuffer.ready = false
             inputBuffer.notify
           }

           var heap_ctx : Long = -1L
           if (entryPoint.requiresHeap) {
             heap_ctx = OpenCLBridge.acquireHeap(ctx, dev_ctx, heapArgStart)
             OpenCLBridge.setupArguments(ctx)
           }
           do {
             val runStart = System.currentTimeMillis // PROFILE

             OpenCLBridge.run(ctx, dev_ctx, thisNLoaded,
                     CLMappedRDDStorage.cl_local_size, lastArgIndex + 1, ntries,
                     heapArgStart)
             RuntimeUtil.profPrint("Run", runStart, threadId) // PROFILE

             if (entryPoint.requiresHeap) {
               val callbackStart = System.currentTimeMillis // PROFILE
               OpenCLBridge.fetchIntArrayArg(ctx, dev_ctx, heapTopArgNum,
                       heapTopBuffer, 1)
               complete = (heapTopBuffer(0) <= heapSize)
               nativeOutputBuffer.kernelAttemptCallback(thisNLoaded,
                       heapArgStart + 3, outArgNum, heapArgStart, heapSize, ctx,
                       dev_ctx, devicePointerSize, heapTopBuffer(0))
               RuntimeUtil.profPrint("KernelAttemptCallback", callbackStart, threadId) // PROFILE
             }
             ntries += 1 // PROFILE
           } while (!complete)

           if (entryPoint.requiresHeap) {
             OpenCLBridge.releaseHeap(dev_ctx, heap_ctx);
           }

           System.err.println("Thread " + threadId + " performed " + ntries + " kernel retries") // PROFILE
           val readStart = System.currentTimeMillis // PROFILE

           nativeOutputBuffer.finish(ctx, dev_ctx, outArgNum, thisNLoaded)
           OpenCLBridge.postKernelCleanup(ctx);
           outputBuffer = Some(nativeOutputBuffer)

           RuntimeUtil.profPrint("Read", readStart, threadId) // PROFILE
         }
       }

       outputBuffer.get.next
     }

     def hasNext() : Boolean = {
       /*
        * hasNext may be called multiple times after running out of items, in
        * which case inputBuffer may already be null on entry to this function.
        */
       val haveNext = inputBuffer != null && (!noMoreInputBuffering || outputBuffer.get.hasNext)
       if (!haveNext && inputBuffer != null) {
         inputBuffer = null
         nativeOutputBuffer = null
         OpenCLBridge.cleanupSwatContext(ctx)
         RuntimeUtil.profPrint("Total", overallStart, threadId) // PROFILE
         System.err.println("Total loaded = " + totalNLoaded) // PROFILE
       }
       haveNext
     }
    }

    iter
  }
}
