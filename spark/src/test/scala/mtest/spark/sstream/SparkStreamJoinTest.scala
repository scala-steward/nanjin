package mtest.spark.sstream

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.chenharryhua.nanjin.spark.AvroTypedEncoder
import com.github.chenharryhua.nanjin.spark.kafka._
import com.github.chenharryhua.nanjin.spark.persist.loaders
import frameless.{TypedDataset, TypedEncoder}
import fs2.Stream
import mtest.spark.kafka.sparKafka
import mtest.spark.sparkSession
import org.apache.spark.sql.streaming.StreamingQueryProgress
import org.apache.spark.sql.{Dataset, SparkSession}
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._
import scala.util.Random

object StreamJoinTestData {
  implicit val ss: SparkSession = sparkSession
  final case class Foo(index: Int, name: String)

  object Foo {
    implicit val teFoo: TypedEncoder[Foo] = shapeless.cachedImplicit
  }

  final case class Bar(index: Int, age: Int)

  object Bar {
    implicit val teBar: TypedEncoder[Bar] = shapeless.cachedImplicit
  }
  final case class FooBar(index: Int, name: String, age: Int)

  object FooBar {
    implicit val teFooBar: TypedEncoder[FooBar] = shapeless.cachedImplicit
  }

  val rand = Random.nextInt()

  val barDS: Dataset[Bar] = TypedDataset.create(List(Bar(rand + 1, 1), Bar(rand + 2, 2), Bar(rand + 3, 3))).dataset

  val fooTopic = sparKafka.topic[Int, Foo]("spark.stream.table.join.test")

  val fooData: List[NJProducerRecord[Int, Foo]] = List
    .fill(50)(Foo(0, "a"))
    .zipWithIndex
    .map { case (foo, idx) => foo.copy(index = rand + idx) }
    .map(x => NJProducerRecord[Int, Foo](x))
}

@DoNotDiscover
class SparkStreamJoinTest extends AnyFunSuite {

  import StreamJoinTestData._
  import sparkSession.implicits._
  test("spark kafka stream-table join") {
    val path = "./data/test/spark/sstream/stream-table-join"
    val sender =
      fooTopic
        .prRdd(fooData)
        .withInterval(0.5.seconds)
        .uploadByBatch
        .updateProducer(_.withClientId("spark.kafka.stream.join.test"))
        .run

    val ss: Stream[IO, StreamingQueryProgress] =
      fooTopic.sstream.transform { fooDS =>
        fooDS.joinWith(barDS, fooDS("value.index") === barDS("index"), "inner").flatMap { case (foo, bar) =>
          foo.value.map(x => FooBar(bar.index, x.name, bar.age))
        }
      }.fileSink(path).triggerEvery(1.seconds).queryStream

    ss.concurrently(sender.delayBy(3.seconds)).interruptAfter(10.seconds).compile.drain.unsafeRunSync()

    val ate = AvroTypedEncoder[FooBar]
    val res = loaders.json(path, ate, sparkSession).dataset.map(_.index).distinct().collect().toSet
    assert(res.contains(rand + 1))
    assert(res.contains(rand + 2))
    assert(res.contains(rand + 3))
  }
}
