package org.apache.spark.rdd.cl

import scala.reflect.ClassTag

import java.nio.BufferOverflowException
import java.nio.ByteOrder

import com.amd.aparapi.internal.model.Entrypoint
import com.amd.aparapi.internal.model.ClassModel
import com.amd.aparapi.internal.model.ClassModel.NameMatcher
import com.amd.aparapi.internal.model.ClassModel.FieldNameInfo
import com.amd.aparapi.internal.util.UnsafeWrapper

import java.nio.ByteBuffer

class ObjectInputBufferWrapper[T](val nele : Int, val typeName : String,
    val entryPoint : Entrypoint) extends InputBufferWrapper[T] {
  val clazz : java.lang.Class[_] = Class.forName(typeName)
  val constructor = OpenCLBridge.getDefaultConstructor(clazz)
  val classModel : ClassModel = entryPoint.getModelFromObjectArrayFieldsClasses(
      typeName, new NameMatcher(typeName))
  val structMemberTypes : Option[Array[Int]] = if (classModel == null) None else
      Some(classModel.getStructMemberTypes)
  val structMemberOffsets : Option[Array[Long]] = if (classModel == null) None else
      Some(classModel.getStructMemberOffsets)
  val structSize = classModel.getTotalStructSize
  val bb : ByteBuffer = ByteBuffer.allocate(classModel.getTotalStructSize * nele)
  bb.order(ByteOrder.LITTLE_ENDIAN)

  var iter : Int = 0
  var objCount : Int = 0

  override def hasSpace() : Boolean = {
    bb.position < bb.capacity
  }

  override def flush() { }

  // override def append(obj : Any) {
  //   append(obj.asInstanceOf[T])
  // }

  override def append(obj : Any) {
    assert(hasSpace())
    OpenCLBridgeWrapper.writeObjectToStream(obj.asInstanceOf[java.lang.Object], classModel, bb)
    objCount += 1
  }

  override def aggregateFrom(iter : Iterator[T]) : Int = {
    val startPosition = bb.position
    while (hasSpace && iter.hasNext) {
      OpenCLBridgeWrapper.writeObjectToStream(
              iter.next.asInstanceOf[java.lang.Object], classModel, bb)
      objCount += 1
    }
    (bb.position - startPosition) / structSize
  }

  override def copyToDevice(argnum : Int, ctx : Long, dev_ctx : Long,
      broadcastId : Int, rddid : Int, partitionid : Int, offset : Int,
      component : Int) : Int = {
    OpenCLBridge.setByteArrayArg(ctx, dev_ctx, argnum, bb.array,
        bb.position, broadcastId, rddid, partitionid, offset, component)
    bb.clear
    return 1
  }

  override def hasNext() : Boolean = {
    iter < objCount
  }

  override def next() : T = {
    val new_obj : T = constructor.newInstance().asInstanceOf[T]
    bb.position(iter * structSize)
    OpenCLBridgeWrapper.readObjectFromStream(new_obj, classModel, bb,
            structMemberTypes.get, structMemberOffsets.get)
    iter += 1
    new_obj
  }
}
