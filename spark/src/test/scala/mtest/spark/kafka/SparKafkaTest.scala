package mtest.spark.kafka

import java.time.{Instant, LocalDate}

import cats.effect.IO
import cats.implicits._
import com.github.chenharryhua.nanjin.kafka.{KafkaTopic, TopicDef}
import com.github.chenharryhua.nanjin.kafka.codec.ManualAvroSchema
import com.github.chenharryhua.nanjin.spark.kafka._
import com.landoop.transportation.nyc.trip.yellow.trip_record
import frameless.cats.implicits._
import io.circe.generic.auto._
import io.circe.syntax._
import org.scalatest.funsuite.AnyFunSuite
import com.github.chenharryhua.nanjin.datetime.iso._
import com.github.chenharryhua.nanjin.kafka.common.NJConsumerRecord
import com.github.chenharryhua.nanjin.spark.injection._
import frameless.TypedDataset

class SparKafkaTest extends AnyFunSuite {
  val embed = EmbeddedForTaskSerializable(0, "embeded")
  val data  = ForTaskSerializable(0, "a", LocalDate.now, Instant.now, embed)
  val topic = ctx.topic[Int, ForTaskSerializable]("serializable.test")

  (topic.admin.idefinitelyWantToDeleteTheTopic >> topic.schemaRegistry.register >>
    topic.send(List(0 -> data, 1 -> data))).unsafeRunSync()

  test("read topic from kafka") {
    val rst =
      topic.kit.sparKafka.fromKafka[IO].flatMap(_.values.collect[IO]()).unsafeRunSync
    assert(rst.toList === List(data, data))
  }

  test("save topic to disk in Jackson format") {
    topic.kit.sparKafka
      .withParamUpdate(_.withOverwrite.withJackson)
      .fromKafka[IO]
      .map(_.save())
      .unsafeRunSync
  }

  test("read topic from disk in Jackson format") {
    val rst = topic.kit.sparKafka
      .withParamUpdate(_.withJackson)
      .fromDisk[IO]
      .values
      .collect[IO]()
      .unsafeRunSync
    assert(rst.toList === List(data, data))
  }

  test("save topic to disk in json format") {
    topic.kit.sparKafka
      .withParamUpdate(_.withOverwrite.withJson)
      .fromKafka[IO]
      .map(_.save())
      .unsafeRunSync
  }

  test("read topic from disk in json format") {
    val rst = topic.kit.sparKafka
      .withParamUpdate(_.withJson)
      .fromDisk[IO]
      .values
      .collect[IO]()
      .unsafeRunSync
    assert(rst.toList === List(data, data))
  }

  test("save topic to disk in parquet format") {
    topic.kit.sparKafka
      .withParamUpdate(_.withOverwrite.withParquet)
      .fromKafka[IO]
      .map(_.save())
      .unsafeRunSync
  }

  test("read topic from disk in parquet format") {
    val rst = topic.kit.sparKafka
      .withParamUpdate(_.withParquet)
      .fromDisk[IO]
      .values
      .collect[IO]()
      .unsafeRunSync
    assert(rst.toList === List(data, data))
  }

  test("save topic to disk in avro format") {
    topic.kit.sparKafka
      .withParamUpdate(_.withOverwrite.withAvro)
      .fromKafka[IO]
      .map(_.save())
      .unsafeRunSync
  }

  test("read topic from disk in avro format") {
    val rst = topic.kit.sparKafka
      .withParamUpdate(_.withAvro)
      .fromDisk[IO]
      .values
      .collect[IO]()
      .unsafeRunSync
    assert(rst.toList === List(data, data))
  }

  test("replay") {
    topic.kit.sparKafka
      .withParamUpdate(
        _.withConversionTactics(_.withoutPartition.withoutTimestamp).withJson.withOverwrite)
      .replay[IO]
      .unsafeRunSync
  }

  test("read topic from kafka and show aggragation result") {
    topic.kit.sparKafka.fromKafka[IO].flatMap(_.stats.minutely).unsafeRunSync
  }

  test("read topic from kafka and show json") {
    val tpk = TopicDef[String, trip_record](
      "nyc_yellow_taxi_trip_data",
      ManualAvroSchema[trip_record](trip_record.schema)).in(ctx)

    tpk.kit.sparKafka
      .fromKafka[IO, String](_.asJson.noSpaces)
      .flatMap(_.show[IO](truncate = false, numRows = 1))
      .unsafeRunSync
  }

  test("should be able to bimap to other topic") {
    val src: KafkaTopic[IO, Int, Int]                = ctx.topic[Int, Int]("src.topic")
    val tgt: KafkaTopic[IO, String, Int]             = ctx.topic[String, Int]("target.topic")
    val d1: NJConsumerRecord[Int, Int]               = NJConsumerRecord(0, 1, 0, None, Some(1), "t", 0)
    val d2: NJConsumerRecord[Int, Int]               = NJConsumerRecord(0, 2, 0, None, None, "t", 0)
    val d3: NJConsumerRecord[Int, Int]               = NJConsumerRecord(0, 3, 0, None, Some(3), "t", 0)
    val d4: NJConsumerRecord[Int, Int]               = NJConsumerRecord(0, 4, 0, None, Some(4), "t", 0)
    val ds: TypedDataset[NJConsumerRecord[Int, Int]] = TypedDataset.create(List(d1, d2, d3, d4))

    val birst: List[Int] =
      src.kit.sparKafka
        .crDataset[IO](ds)
        .bimapTo(tgt.kit)(_.toString, _ + 1)
        .values
        .collect[IO]()
        .unsafeRunSync
        .toList
    assert(birst == List(2, 4, 5))
  }

  test("should be able to flatmap to other topic") {
    val src: KafkaTopic[IO, Int, Int]                = ctx.topic[Int, Int]("src.topic")
    val tgt: KafkaTopic[IO, Int, Int]                = ctx.topic[Int, Int]("target.topic")
    val d1: NJConsumerRecord[Int, Int]               = NJConsumerRecord(0, 1, 0, None, Some(1), "t", 0)
    val d2: NJConsumerRecord[Int, Int]               = NJConsumerRecord(0, 2, 0, None, None, "t", 0)
    val d3: NJConsumerRecord[Int, Int]               = NJConsumerRecord(0, 3, 0, None, Some(3), "t", 0)
    val d4: NJConsumerRecord[Int, Int]               = NJConsumerRecord(0, 4, 0, None, Some(4), "t", 0)
    val ds: TypedDataset[NJConsumerRecord[Int, Int]] = TypedDataset.create(List(d1, d2, d3, d4))

    val birst: List[Int] =
      src.kit.sparKafka
        .crDataset[IO](ds)
        .flatMapTo(tgt.kit)(m => m.value.map(x => NJConsumerRecord.value.set(Some(x - 1))(m)))
        .values
        .collect[IO]()
        .unsafeRunSync
        .toList
    assert(birst == List(0, 2, 3))
  }

  test("someValue should filter out none values") {
    val cr1: NJConsumerRecord[Int, Int]              = NJConsumerRecord(0, 1, 0, None, Some(1), "t", 0)
    val cr2: NJConsumerRecord[Int, Int]              = NJConsumerRecord(0, 2, 0, Some(2), None, "t", 0)
    val cr3: NJConsumerRecord[Int, Int]              = NJConsumerRecord(0, 3, 0, Some(3), None, "t", 0)
    val crs: List[NJConsumerRecord[Int, Int]]        = List(cr1, cr2, cr3)
    val ds: TypedDataset[NJConsumerRecord[Int, Int]] = TypedDataset.create(crs)

    val t   = TopicDef[Int, Int]("some.value").in(ctx).kit.sparKafka.crDataset(ds)
    val rst = t.someValues.typedDataset.collect[IO]().unsafeRunSync()
    assert(rst === Seq(cr1))
  }
}
