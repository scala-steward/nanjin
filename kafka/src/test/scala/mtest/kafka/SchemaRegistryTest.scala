package mtest.kafka

import cats.effect.IO
import com.github.chenharryhua.nanjin.kafka.{KafkaSettings, KafkaTopic, TopicDef}
import org.scalatest.funsuite.AnyFunSuite

class SchemaRegistryTest extends AnyFunSuite {

  val nyc: TopicDef[Int, trip_record] =
    TopicDef[Int, trip_record]("nyc_yellow_taxi_trip_data")

  val topic: KafkaTopic[IO, Int, trip_record] = nyc.in(ctx)

  test("latest schema") {
    topic.schemaRegistry.latestMeta.map(_.show).unsafeRunSync()
  }
  test("compatiable test") {
    topic.schemaRegistry.testCompatibility.map(println).unsafeRunSync
  }
  test("register schema") {
    topic.schemaRegistry.register.unsafeRunSync()
  }
  test("schema registry is not necessarily configured if it is not used") {
    val noRegistry = KafkaSettings.empty.withBrokers("localhost:9092").ioContext
    val topic      = noRegistry.topic[Int, Int]("no_schema_registry_test")
    topic.send(1, 1).unsafeRunSync()
  }
}
