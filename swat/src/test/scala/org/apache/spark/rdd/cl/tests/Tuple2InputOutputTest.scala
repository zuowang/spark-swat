package org.apache.spark.rdd.cl.tests

import java.util.LinkedList

import com.amd.aparapi.internal.writer.BlockWriter.ScalaArrayParameter
import com.amd.aparapi.internal.model.Tuple2ClassModel
import com.amd.aparapi.internal.model.ClassModel
import com.amd.aparapi.internal.model.HardCodedClassModels

import org.apache.spark.rdd.cl.CodeGenTest
import org.apache.spark.rdd.cl.CodeGenUtil

object Tuple2InputOutputTest extends CodeGenTest[(Int, Float), (Int, Point)] {
  def getExpectedKernel() : String = {
    "#pragma OPENCL EXTENSION cl_khr_global_int32_base_atomics : enable\n" +
    "#pragma OPENCL EXTENSION cl_khr_global_int32_extended_atomics : enable\n" +
    "#pragma OPENCL EXTENSION cl_khr_local_int32_base_atomics : enable\n" +
    "#pragma OPENCL EXTENSION cl_khr_local_int32_extended_atomics : enable\n" +
    "static int atomicAdd(__global int *_arr, int _index, int _delta){\n" +
    "   return atomic_add(&_arr[_index], _delta);\n" +
    "}\n" +
    "static __global void *alloc(__global void *heap, volatile __global uint *free_index, unsigned int heap_size, int nbytes, int *alloc_failed) {\n" +
    "   __global unsigned char *cheap = (__global unsigned char *)heap;\n" +
    "   uint offset = atomic_add(free_index, nbytes);\n" +
    "   if (offset + nbytes > heap_size) { *alloc_failed = 1; return 0x0; }\n" +
    "   else return (__global void *)(cheap + offset);\n" +
    "}\n" +
    "\n" +
    "typedef struct __attribute__ ((packed)) org_apache_spark_rdd_cl_tests_Point_s{\n" +
    "   float  x;\n" +
    "   float  y;\n" +
    "   float  z;\n" +
    "   \n" +
    "} org_apache_spark_rdd_cl_tests_Point;\n" +
    "\n" +
    "typedef struct __attribute__ ((packed)) scala_Tuple2_I_F_s{\n" +
    "   int  _1;\n" +
    "   float  _2;\n" +
    "   \n" +
    "} scala_Tuple2_I_F;\n" +
    "\n" +
    "typedef struct __attribute__ ((packed)) scala_Tuple2_I_org_apache_spark_rdd_cl_tests_Point_s{\n" +
    "   __global org_apache_spark_rdd_cl_tests_Point  * _2;\n" +
    "   int  _1;\n" +
    "   \n" +
    "} scala_Tuple2_I_org_apache_spark_rdd_cl_tests_Point;\n" +
    "typedef struct This_s{\n" +
    "   __global void *heap;\n" +
    "   __global uint *free_index;\n" +
    "   int alloc_failed;\n" +
    "   unsigned int heap_size;\n" +
    "   } This;\n" +
    "\n" +
    "static __global scala_Tuple2_I_org_apache_spark_rdd_cl_tests_Point *scala_Tuple2_I_org_apache_spark_rdd_cl_tests_Point___init_(__global scala_Tuple2_I_org_apache_spark_rdd_cl_tests_Point *this, int  one, __global org_apache_spark_rdd_cl_tests_Point *  two) {\n" +
    "   this->_1 = one;\n" +
    "   this->_2 = two;\n" +
    "   return this;\n" +
    "}\n" +
    "\n" +
    "static __global org_apache_spark_rdd_cl_tests_Point * org_apache_spark_rdd_cl_tests_Point___init_(__global org_apache_spark_rdd_cl_tests_Point *this, float x, float y, float z){\n" +
    "   this->x=x;\n" +
    "   this->y=y;\n" +
    "   this->z=z;\n" +
    "   (this);\n" +
    "   return (this);\n" +
    "}\n" +
    "static __global scala_Tuple2_I_org_apache_spark_rdd_cl_tests_Point *org_apache_spark_rdd_cl_tests_Tuple2InputOutputTest$$anon$1__apply(This *this, __global scala_Tuple2_I_F* in){\n" +
    "   __global scala_Tuple2_I_org_apache_spark_rdd_cl_tests_Point * __alloc0 = (__global scala_Tuple2_I_org_apache_spark_rdd_cl_tests_Point *)alloc(this->heap, this->free_index, this->heap_size, sizeof(scala_Tuple2_I_org_apache_spark_rdd_cl_tests_Point), &this->alloc_failed);\n" +
    "   if (this->alloc_failed) { return (0x0); }\n" +
    "   __global org_apache_spark_rdd_cl_tests_Point * __alloc1 = (__global org_apache_spark_rdd_cl_tests_Point *)alloc(this->heap, this->free_index, this->heap_size, sizeof(org_apache_spark_rdd_cl_tests_Point), &this->alloc_failed);\n" +
    "   if (this->alloc_failed) { return (0x0); }\n" +
    "   return(scala_Tuple2_I_org_apache_spark_rdd_cl_tests_Point___init_(__alloc0, in->_1, org_apache_spark_rdd_cl_tests_Point___init_(__alloc1, (in->_2 + 1.0f), (in->_2 + 2.0f), (in->_2 + 3.0f))));\n" +
    "}\n" +
    "__kernel void run(\n" +
    "      __global int * in0_1, __global float * in0_2, __global scala_Tuple2_I_F *in0, \n" +
    "      __global int * out_1, __global org_apache_spark_rdd_cl_tests_Point* out_2, __global void *heap, __global uint *free_index, unsigned int heap_size, __global int *processing_succeeded, __global int *any_failed, int N) {\n" +
    "   int i = get_global_id(0);\n" +
    "   int nthreads = get_global_size(0);\n" +
    "   This thisStruct;\n" +
    "   This* this=&thisStruct;\n" +
    "   this->heap = heap;\n" +
    "   this->free_index = free_index;\n" +
    "   this->heap_size = heap_size;\n" +
    "   __global scala_Tuple2_I_F *my_in0 = in0 + get_global_id(0);\n" +
    "   for (; i < N; i += nthreads) {\n" +
    "      if (processing_succeeded[i]) continue;\n" +
    "      \n" +
    "      this->alloc_failed = 0;\n" +
    "      my_in0->_1 = in0_1[i];\n" +
    "      my_in0->_2 = in0_2[i];\n" +
    "      __global scala_Tuple2_I_org_apache_spark_rdd_cl_tests_Point* result = org_apache_spark_rdd_cl_tests_Tuple2InputOutputTest$$anon$1__apply(this, my_in0);\n" +
    "      if (this->alloc_failed) {\n" +
    "         processing_succeeded[i] = 0;\n" +
    "         *any_failed = 1;\n" +
    "      } else {\n" +
    "         processing_succeeded[i] = 1;\n" +
    "         out_1[i] = result->_1;\n" +
    "         out_2[i] = *(result->_2);\n" +
    "      }\n" +
    "   }\n" +
    "}\n"
  }

  def getExpectedNumInputs() : Int = {
    1
  }

  def init() : HardCodedClassModels = {
    val models = new HardCodedClassModels()

    val inputClassType1Name = CodeGenUtil.cleanClassName("I")
    val inputClassType2Name = CodeGenUtil.cleanClassName("F")
    val inputTuple2ClassModel : Tuple2ClassModel = Tuple2ClassModel.create(
        inputClassType1Name, inputClassType2Name, false)

    val outputClassType1Name = CodeGenUtil.cleanClassName("I")
    val outputClassType2Name = CodeGenUtil.cleanClassName("org.apache.spark.rdd.cl.tests.Point")
    val outputTuple2ClassModel : Tuple2ClassModel = Tuple2ClassModel.create(
        outputClassType1Name, outputClassType2Name, true)

    models.addClassModelFor(classOf[Tuple2[_, _]], inputTuple2ClassModel)
    models.addClassModelFor(classOf[Tuple2[_, _]], outputTuple2ClassModel)

    models
  }

  def complete(params : LinkedList[ScalaArrayParameter]) {
    params.get(0).addTypeParameter("I", false)
    params.get(0).addTypeParameter("F", false)

    params.get(1).addTypeParameter("I", false)
    params.get(1).addTypeParameter("Lorg.apache.spark.rdd.cl.tests.Point;", true)
  }

  def getFunction() : Function1[(Int, Float), (Int, Point)] = {
    new Function[(Int, Float), (Int, Point)] {
      override def apply(in : (Int, Float)) : (Int, Point) = {
        (in._1, new Point(in._2 + 1.0f, in._2 + 2.0f, in._2 + 3.0f))
      }
    }
  }
}
