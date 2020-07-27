package mtest.spark.kafka

import cats.effect.IO
import cats.implicits._
import com.github.chenharryhua.nanjin.kafka.{KafkaTopic, TopicDef, TopicName}
import com.github.chenharryhua.nanjin.spark.kafka._
import fs2.kafka.ProducerRecord
import org.scalatest.funsuite.AnyFunSuite
import shapeless.{:+:, CNil, Coproduct}
import io.circe.generic.auto._
import io.circe.shapes._
import scala.util.Random

object MultiSaveTestData {
  final case class Apple(size: Int, locale: String)
  final case class WaterMelon(size: Int, weight: Float)
  final case class Orange(size: Int, locale: String)
  type Fruit = Apple :+: WaterMelon :+: Orange :+: CNil
  final case class Food(fruit: Fruit, num: Int)

  val food = List(
    Food(Coproduct[Fruit](Apple(1, "qidong")), Random.nextInt),
    Food(Coproduct[Fruit](WaterMelon(2, 1.1f)), Random.nextInt),
    Food(Coproduct[Fruit](Orange(3, "hunan")), Random.nextInt),
    Food(Coproduct[Fruit](Apple(4, "chengdu")), Random.nextInt),
    Food(Coproduct[Fruit](WaterMelon(5, 1.1f)), Random.nextInt),
    Food(Coproduct[Fruit](Orange(6, "wuhan")), Random.nextInt),
    Food(Coproduct[Fruit](WaterMelon(7, 1.1f)), Random.nextInt),
    Food(Coproduct[Fruit](Apple(8, "chongqin")), Random.nextInt),
    Food(Coproduct[Fruit](WaterMelon(9, 1.1f)), Random.nextInt),
    Food(Coproduct[Fruit](Orange(10, "shanghai")), Random.nextInt)
  )

  val topic: KafkaTopic[IO, Int, Food] =
    TopicDef[Int, Food](TopicName("test.spark.kafka.multi.save")).in(ctx)

  val prs: List[ProducerRecord[Int, Food]] = food.map(f => topic.fs2PR(0, f))

}

class MultiSaveTest extends AnyFunSuite {
  import MultiSaveTestData._

  (topic.admin.idefinitelyWantToDeleteTheTopicAndUnderstoodItsConsequence >>
    topic.send(prs)).unsafeRunSync()

  test("multi-save avro") {
    val path = "./data/test/spark/kafka/multi/avro"

    val rst =
      topic.sparKafka.fromKafka.flatMap(_.repartition(3).save.multi(blocker).avro(path)) >> IO {
        topic.sparKafka.load.avro(path).rdd.collect.flatMap(_.value).toSet
      }
    assert(rst.unsafeRunSync() == food.toSet)
  }
  test("multi-save jackson") {
    val path = "./data/test/spark/kafka/multi/jackson"
    val rst =
      topic.sparKafka.fromKafka.flatMap(_.repartition(3).save.multi(blocker).jackson(path)) >> IO {
        topic.sparKafka.load.jackson(path).rdd.collect().flatMap(_.value).toSet
      }
    assert(rst.unsafeRunSync() == food.toSet)
  }
}
