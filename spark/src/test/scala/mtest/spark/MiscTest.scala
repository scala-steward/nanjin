package mtest.spark

import cats.derived.auto.eq._
import cats.kernel.Eq
import com.github.chenharryhua.nanjin.spark.injection._
import frameless.{TypedDataset, TypedEncoder, TypedExpressionEncoder}
import mtest.spark.pb.test.Whale
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

object JoinTestData {
  final case class Brother(id: Int, rel: String)
  final case class Sister(id: Int, sibling: String)

  implicit val se: TypedEncoder[Sister]  = shapeless.cachedImplicit
  implicit val be: TypedEncoder[Brother] = shapeless.cachedImplicit

  val brothers = List(
    Brother(1, "a"),
    Brother(1, "b"),
    Brother(2, "c")
  )

  val sisters = List(
    Sister(1, "x"),
    Sister(1, "y"),
    Sister(3, "z")
  )

  val rdd: RDD[Sister] = sparkSession.sparkContext.parallelize(sisters)

  object Fruit extends Enumeration {
    val Apple, Watermelon = Value
  }

  final case class Food(kind: Fruit.Value, num: Int)

  val shouldCompile: Eq[Food] = implicitly[Eq[Food]]
}

class MiscTest extends AnyFunSuite {
  import JoinTestData._
  implicit val ss: SparkSession = sparkSession
  test("spark left join") {
    val db = TypedDataset.create(brothers)
    val ds = TypedDataset.create(sisters)

    val res = db.joinLeft(ds)(db('id) === ds('id))
    res.dataset.show(truncate = false)

  }

  test("typed encoder of scalapb generate case class") {
    import scalapb.spark.Implicits._
    val pt: TypedEncoder[Whale] = TypedEncoder[Whale] // should compile
    val whales = List(
      Whale("aaa", Random.nextInt()),
      Whale("bbb", Random.nextInt()),
      Whale("ccc", Random.nextInt())
    )
    val path = "./data/test/spark/protobuf/whales.pb"
    TypedDataset.create(whales).write.mode(SaveMode.Overwrite).parquet(path)
    val rst =
      TypedDataset.createUnsafe[Whale](sparkSession.read.parquet(path)).dataset.collect().toSet
    assert(rst == whales.toSet)
  }

  test("gen case class") {
    val tds = TypedDataset.create(rdd)(se, sparkSession)
    tds.printSchema()
  }

  test("gen schema") {
    import sparkSession.implicits._
    val s             = TypedExpressionEncoder.targetStructType(se)
    val df: DataFrame = sparkSession.createDataFrame(rdd.toDF().rdd, s)
    println(s)

    val tds: TypedDataset[Sister] = TypedDataset.create(rdd)
    df.printSchema()
    tds.printSchema()
  }
}
