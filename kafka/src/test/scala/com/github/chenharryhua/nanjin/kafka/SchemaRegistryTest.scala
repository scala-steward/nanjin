package com.github.chenharryhua.nanjin.kafka

import cats.effect.IO
import org.scalatest.FunSuite

class SchemaRegistryTest extends FunSuite {

  val topic =
    KafkaTopicName("nyc_yellow_taxi_trip_data").in[KAvro[reddit_post_key], KAvro[reddit_post]](ctx)
  test("latest schema") {
    topic.schemaRegistry[IO].latestMeta.map(_.show).map(println).unsafeRunSync()
  }
  test("compatiable test") {
    topic.schemaRegistry[IO].testCompatibility.map(println).unsafeRunSync
  }
  ignore("register schema") {
    topic.schemaRegistry[IO].register.unsafeRunSync()
  }
}
