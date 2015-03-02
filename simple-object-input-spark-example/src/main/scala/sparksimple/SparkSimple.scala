import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.rdd.cl._
import Array._
import scala.math._
import org.apache.spark.rdd._
import java.net._

class Point(val x: Float, val y: Float, val z: Float) { }

object SparkSimple {
    def main(args : Array[String]) {
        if (args.length < 1) {
            println("usage: SparkSimple cmd")
            return;
        }

        val cmd = args(0)

        if (cmd == "convert") {
            convert(args.slice(1, args.length))
        } else if (cmd == "run") {
            run_simple(args.slice(1, args.length))
        } else if (cmd == "run-cl") {
            run_simple_cl(args.slice(1, args.length))
        } else if (cmd == "check") {
            val correct : Array[Double] = run_simple(args.slice(1, args.length))
            val actual : Array[Double] = run_simple_cl(args.slice(1, args.length))
            assert(correct.length == actual.length)
            for (i <- 0 until correct.length) {
                val a = correct(i)
                val b = actual(i)
                assert(a == b)
            }
        }
    }

    def get_spark_context(appName : String) : SparkContext = {
        val conf = new SparkConf()
        conf.setAppName(appName)

        val localhost = InetAddress.getLocalHost
        conf.setMaster("spark://" + localhost.getHostName + ":7077") // 7077 is the default port

        return new SparkContext(conf)
    }

    def run_simple(args : Array[String]) : Array[Double] = {
        if (args.length != 1) {
            println("usage: SparkSimple run input-path");
            return new Array[Double](0);
        }
        val sc = get_spark_context("Spark Simple");

        val m : Double = 4.0

        val arr : Array[Point] = new Array[Point](3)
        arr(0) = new Point(0, 1, 2)
        arr(1) = new Point(3, 4, 5)
        arr(2) = new Point(6, 7, 8)

        val inputPath = args(0)
        val inputs : RDD[Point] = sc.objectFile[Point](inputPath).cache
        val outputs : Array[Double] = inputs.map(v => v.x * v.y * v.z * m * arr(1).y).collect
        sc.stop
        outputs
    }

    def run_simple_cl(args : Array[String]) : Array[Double] = {
        if (args.length != 1) {
            println("usage: SparkSimple run-cl input-path");
            return new Array[Double](0)
        }
        val sc = get_spark_context("Spark Simple");

        val m : Double = 4.0

        val arr : Array[Point] = new Array[Point](3)
        arr(0) = new Point(0, 1, 2)
        arr(1) = new Point(3, 4, 5)
        arr(2) = new Point(6, 7, 8)

        val inputPath = args(0)
        val inputs : RDD[Point] = sc.objectFile[Point](inputPath).cache
        val inputs_cl : CLWrapperRDD[Point] = CLWrapper.cl[Point](inputs)
        val outputs : Array[Double] = inputs_cl.map(v => v.x * v.y * v.z * m * arr(1).y).collect
        sc.stop
        outputs
    }

    def convert(args : Array[String]) {
        if (args.length != 2) {
            println("usage: SparkSimple convert input-dir output-dir");
            return
        }
        val sc = get_spark_context("Spark KMeans Converter");

        val inputDir = args(0)
        var outputDir = args(1)
        val input = sc.textFile(inputDir)
        val converted = input.map(line => {
            val tokens : Array[String] = line.split(" ")
            new Point(tokens(0).toFloat, tokens(1).toFloat,
                    tokens(2).toFloat) })
        converted.saveAsObjectFile(outputDir)
    }
}
