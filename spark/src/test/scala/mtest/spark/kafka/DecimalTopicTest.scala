package mtest.spark.kafka

import java.time.Instant

import cats.effect.IO
import cats.implicits._
import com.github.chenharryhua.nanjin.datetime.iso._
import com.github.chenharryhua.nanjin.kafka.TopicName
import com.github.chenharryhua.nanjin.kafka.common.NJConsumerRecord
import com.github.chenharryhua.nanjin.spark._
import com.github.chenharryhua.nanjin.spark.injection._
import com.sksamuel.avro4s.ScalePrecision
import frameless.cats.implicits._
import org.scalatest.funsuite.AnyFunSuite

import scala.math.BigDecimal.RoundingMode

object DecimalTopicTestCase {
  final case class HasDecimal(a: BigDecimal, b: Instant)
  val data = HasDecimal(BigDecimal(1.0101010101), Instant.now)
}

class DecimalTopicTest extends AnyFunSuite {
  import DecimalTopicTestCase._
  implicit val sp           = ScalePrecision(10, 20)
  implicit val roundingMode = RoundingMode.HALF_UP
  val topic                 = ctx.topic[Int, HasDecimal](TopicName("decimal.test"))
  (topic.admin.idefinitelyWantToDeleteTheTopicAndUnderstoodItsConsequence >>
    topic.schemaRegistry.register >>
    topic.send(1, data) >> topic.send(2, data)).unsafeRunSync()

  test("kafka and spark agree on avro") {
    topic.fs2Channel.stream
      .map(m => topic.decoder(m).record)
      .take(2)
      .through(fileSink[IO].avro[NJConsumerRecord[Int, HasDecimal]]("./data/test/decimal.avro"))
      .compile
      .drain
      .unsafeRunSync

    val res: List[HasDecimal] =
      sparkSession
        .avro[NJConsumerRecord[Int, HasDecimal]]("./data/test/decimal.avro")
        .collect[IO]
        .unsafeRunSync
        .toList
        .flatMap(_.value)

    assert(res === List(data, data))
  }

}
