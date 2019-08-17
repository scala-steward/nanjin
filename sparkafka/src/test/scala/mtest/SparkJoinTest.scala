package mtest

import java.time.LocalDateTime

import cats.effect.IO
import cats.implicits._
import com.github.chenharryhua.nanjin.kafka.{utils, NJProducerRecord}
import com.github.chenharryhua.nanjin.sparkafka.Sparkafka
import fs2.Stream
import org.scalatest.FunSuite
import frameless.cats.implicits._

case class FirstStream(name: String, age: Int)
case class SecondStream(name: String, score: Int)

class SparkJoinTest extends FunSuite {
  val num: Int = 10000
  val start    = LocalDateTime.now.minusNanos(num.toLong)
  println(s"start-time: $start")

  val first_data =
    Stream
      .range(0, num)
      .covary[IO]
      .map(
        i =>
          new NJProducerRecord[Int, FirstStream](
            first_topic.topicName,
            0,
            FirstStream(i.toString, utils.random4d.value))
            .updateTimestamp(Some(utils.localDateTime2KafkaTimestamp(start.plusNanos(i.toLong)))))
      .evalMap(msg => first_topic.producer.send(msg))

  val second_data =
    Stream
      .range(0, num)
      .covary[IO]
      .map(
        i =>
          new NJProducerRecord[Int, SecondStream](
            second_topic.topicName,
            0,
            SecondStream(i.toString, utils.random4d.value))
            .updateTimestamp(Some(utils.localDateTime2KafkaTimestamp(start.plusNanos(i.toLong)))))
      .evalMap(msg => second_topic.producer.send(msg))

  test("gen data") {
    println(first_topic.kafkaProducerSettings.show)
    (first_data.compile.drain >> second_data.compile.drain).unsafeRunSync()
  }

  test("spark") {

    spark.use { s =>
      for {
        f <- Sparkafka.valueDataset(s, first_topic, start, start.plusNanos(num.toLong))
        s <- Sparkafka.valueDataset(s, second_topic, start, start.plusNanos(num.toLong))
        _ <- f.show[IO]()
        _ <- s.show[IO]()
        cf <- f.count[IO]()
        cs <- s.count[IO]
        ds <- f.joinInner(s)(f('name) === s('name)).count[IO]()
      } yield println(s"result = $ds cf = $cf, cs = $cs")

    }.unsafeRunSync()
  }
}
