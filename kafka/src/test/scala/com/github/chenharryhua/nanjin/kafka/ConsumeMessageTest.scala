package com.github.chenharryhua.nanjin.kafka

import cats.effect.IO
import cats.implicits._
import KafkaTopicName._
import org.scalatest.FunSuite
import KafkaTopicName._

class ConsumeMessageTest extends FunSuite with ShowKafkaMessage with Fs2MessageBitraverse {

  test("consume json topic") {
    import io.circe.generic.auto._
    val jsonTopic =
      topic"backblaze_smart".in[IO, KJson[lenses_record_key], KJson[lenses_record]](ctx)

    jsonTopic
      .fs2Stream[IO]
      .consumeNativeMessages
      .map(_.bitraverse(identity, identity).toEither)
      .rethrow
      .map(_.show)
      .showLinesStdOut
      .take(3)
      .compile
      .drain
      .unsafeRunSync()
  }
  test("consume avro topic") {
    import cats.derived.auto.show._
    val avroTopic =
      KafkaTopicName("nyc_yellow_taxi_trip_data").in[IO, Array[Byte], KAvro[trip_record]](ctx)

    avroTopic
      .fs2Stream[IO]
      .consumeValues
      .map(_.toEither)
      .rethrow
      .map(_.show)
      .showLinesStdOut
      .take(3)
      .compile
      .drain
      .unsafeRunSync()
  }

  test("payments") {
    val avroTopic =
      KafkaTopicName("cc_payments").in[IO, String, KAvro[Payment]](ctx)

    avroTopic
      .fs2Stream[IO]
      .consumeMessages
      .map(_.toEither)
      .rethrow
      .map(_.show)
      .showLinesStdOut
      .take(3)
      .compile
      .drain
      .unsafeRunSync()
  }
}
