package com.github.chenharryhua.nanjin.kafka

import cats.derived.auto.show._
import cats.implicits._
import com.github.chenharryhua.nanjin.codec.{KJson, _}
import fs2.kafka.AutoOffsetReset
import io.circe.generic.auto._
import org.scalatest.funsuite.AnyFunSuite

class ConsumeMessageFs2Test extends AnyFunSuite  {
  val backblaze_smart = TopicDef[KJson[lenses_record_key], KJson[lenses_record]]("backblaze_smart")
  val nyc_taxi_trip   = TopicDef[Array[Byte], trip_record]("nyc_yellow_taxi_trip_data")
  test("should be able to consume json topic") {
    val chn = backblaze_smart.in(ctx).fs2Channel
    val ret =
      chn
        .updateConsumerSettings(_.withAutoOffsetReset(AutoOffsetReset.Earliest))
        .consume
        .map(chn.messageDecoder.tryDecodeKeyValue)
        .take(3)
        .map(_.show)
        .map(println)
        .compile
        .toList
        .unsafeRunSync()
    assert(ret.size == 3)
  }
  test("should be able to consume avro topic") {
    import cats.derived.auto.show._
    val chn = ctx.topic(nyc_taxi_trip).fs2Channel
    val ret = chn.consume
      .map(chn.messageDecoder.decodeValue)
      .take(3)
      .map(_.show)
      .map(println)
      .compile
      .toList
      .unsafeRunSync()
    assert(ret.size == 3)
  }

  ignore("should be able to consume payments topic") {
    val chn = ctx.topic[String, Payment]("cc_payments").fs2Channel
    val ret = chn.consume
      .map(chn.messageDecoder.tryDecode)
      .map(_.toEither)
      .rethrow
      .take(3)
      .map(_.show)
      .compile
      .toList
      .unsafeRunSync()
    assert(ret.size == 3)
  }
}
