package org.apache.spark.rdd.cl.tests

import java.util.LinkedList

import com.amd.aparapi.internal.writer.ScalaArrayParameter
import com.amd.aparapi.internal.model.Tuple2ClassModel
import com.amd.aparapi.internal.model.ClassModel
import com.amd.aparapi.internal.model.HardCodedClassModels
import com.amd.aparapi.internal.model.DenseVectorClassModel
import com.amd.aparapi.internal.model.ScalaArrayClassModel

import org.apache.spark.rdd.cl.SyncCodeGenTest
import org.apache.spark.rdd.cl.CodeGenTest
import org.apache.spark.rdd.cl.CodeGenTests
import org.apache.spark.rdd.cl.CodeGenUtil

import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.mllib.linalg.Vectors

import org.apache.spark.rdd.cl.DenseVectorInputBufferWrapperConfig

object ArrayOutputTest extends SyncCodeGenTest[Int, Array[Double]] {
  def getExpectedException() : String = { return null }

  def getExpectedKernel() : String = { getExpectedKernelHelper(getClass) }

  def getExpectedNumInputs : Int = {
    1
  }

  def init() : HardCodedClassModels = {
    val models = new HardCodedClassModels()
    val arrayModel = ScalaArrayClassModel.create("D")
    models.addClassModelFor(classOf[Array[_]], arrayModel)
    models
  }

  def complete(params : LinkedList[ScalaArrayParameter]) {
  }

  def getFunction() : Function1[Int, Array[Double]] = {
    new Function[Int, Array[Double]] {
      override def apply(in : Int) : Array[Double] = {
        val valuesArr = new Array[Double](in)

        var i = 0
        while (i < in) {
          valuesArr(i) = 2 * i
          i += 1
        }
        valuesArr
      }
    }
  }
}
