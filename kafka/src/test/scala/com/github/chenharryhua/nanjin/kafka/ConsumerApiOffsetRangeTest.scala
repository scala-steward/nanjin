package com.github.chenharryhua.nanjin.kafka

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.chenharryhua.nanjin.datetime.zones.darwinTime
import com.github.chenharryhua.nanjin.datetime.{NJDateTimeRange, NJTimestamp}
import eu.timepit.refined.auto.*
import fs2.Stream
import fs2.kafka.{ConsumerSettings, ProducerRecord, ProducerRecords, ProducerResult}
import mtest.kafka.ctx
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.scalatest.funsuite.AnyFunSuite

class ConsumerApiOffsetRangeTest extends AnyFunSuite {

  /** * Notes:
    *
    * ---------------100-------200-------300-------> Time ----------------| |------ before beginning after
    * ending
    *
    * ^ ^ \| | start end
    */

  val topic: KafkaTopic[IO, Int, Int] = ctx.topic[Int, Int]("range.test")

  val pr1: ProducerRecord[Int, Int] = ProducerRecord(topic.topicName.value, 1, 1).withTimestamp(100)
  val pr2: ProducerRecord[Int, Int] = ProducerRecord(topic.topicName.value, 2, 2).withTimestamp(200)
  val pr3: ProducerRecord[Int, Int] = ProducerRecord(topic.topicName.value, 3, 3).withTimestamp(300)

  val topicData: Stream[IO, ProducerResult[Int, Int]] =
    Stream(ProducerRecords(List(pr1, pr2, pr3))).covary[IO].through(topic.produce.pipe)

  (ctx.admin(topic.topicName).iDefinitelyWantToDeleteTheTopicAndUnderstoodItsConsequence.attempt >>
    topicData.compile.drain).unsafeRunSync()

  val transientConsumer: TransientConsumer[IO] = {
    val cs: ConsumerSettings[Id, Nothing, Nothing] = ConsumerSettings[Id, Nothing, Nothing](null, null)
    TransientConsumer[IO](
      topic.topicName,
      cs.withProperties(topic.context.settings.consumerSettings.properties).withGroupId("consumer-api-test"))
  }

  test("start and end are both in range") {
    val expect: KafkaTopicPartition[Option[KafkaOffsetRange]] =
      KafkaTopicPartition(
        Map(new TopicPartition("range.test", 0) ->
          KafkaOffsetRange(KafkaOffset(1), KafkaOffset(2))))

    val r = NJDateTimeRange(darwinTime).withStartTime(110).withEndTime(250)

    transientConsumer.offsetRangeFor(r).map(x => assert(x === expect)).unsafeRunSync()
  }

  test("start is equal to beginning and end is equal to ending") {
    val expect: KafkaTopicPartition[Option[KafkaOffsetRange]] =
      KafkaTopicPartition(
        Map(new TopicPartition("range.test", 0) ->
          KafkaOffsetRange(KafkaOffset(0), KafkaOffset(2))))

    val r = NJDateTimeRange(darwinTime).withStartTime(100).withEndTime(300)

    transientConsumer.offsetRangeFor(r).map(x => assert(x === expect)).unsafeRunSync()
  }

  test("start is equal to beginning and end is after ending") {
    val expect: KafkaTopicPartition[Option[KafkaOffsetRange]] =
      KafkaTopicPartition(
        Map(new TopicPartition("range.test", 0) ->
          KafkaOffsetRange(KafkaOffset(0), KafkaOffset(3))))

    val r = NJDateTimeRange(darwinTime).withStartTime(100).withEndTime(310)

    transientConsumer.offsetRangeFor(r).map(x => assert(x === expect)).unsafeRunSync()
  }

  test("start after beginning and end after ending") {
    val expect =
      KafkaTopicPartition(
        Map(new TopicPartition("range.test", 0) ->
          KafkaOffsetRange(KafkaOffset(1), KafkaOffset(3))))

    val r = NJDateTimeRange(darwinTime).withStartTime(110).withEndTime(500)

    transientConsumer.offsetRangeFor(r).map(x => assert(x === expect)).unsafeRunSync()
  }

  test("start before beginning and end before ending") {
    val expect =
      KafkaTopicPartition(
        Map(new TopicPartition("range.test", 0) ->
          KafkaOffsetRange(KafkaOffset(0), KafkaOffset(1))))

    val r = NJDateTimeRange(darwinTime).withStartTime(10).withEndTime(110)

    transientConsumer.offsetRangeFor(r).map(x => assert(x === expect)).unsafeRunSync()
  }

  test("both start and end are before beginning") {
    val expect =
      KafkaTopicPartition(Map(new TopicPartition("range.test", 0) -> None))

    val r = NJDateTimeRange(darwinTime).withStartTime(10).withEndTime(30)

    transientConsumer.offsetRangeFor(r).map(x => assert(x === expect)).unsafeRunSync()
  }

  test("both start and end are after ending") {
    val expect =
      KafkaTopicPartition(Map(new TopicPartition("range.test", 0) -> None))

    val r = NJDateTimeRange(darwinTime).withStartTime(500).withEndTime(600)

    transientConsumer.offsetRangeFor(r).map(x => assert(x === expect)).unsafeRunSync()
  }

  test("when there is no data in the range") {
    val expect =
      KafkaTopicPartition(Map(new TopicPartition("range.test", 0) -> None))

    val r = NJDateTimeRange(darwinTime).withStartTime(110).withEndTime(120)

    transientConsumer.offsetRangeFor(r).map(x => assert(x === expect)).unsafeRunSync()
  }

  test("time range is infinite") {
    val expect: KafkaTopicPartition[Option[KafkaOffsetRange]] =
      KafkaTopicPartition(
        Map(new TopicPartition("range.test", 0) ->
          KafkaOffsetRange(KafkaOffset(0), KafkaOffset(3))))

    val r = NJDateTimeRange(darwinTime)
    transientConsumer.offsetRangeFor(r).map(x => assert(x === expect)).unsafeRunSync()
  }

  test("kafka offset range") {
    assert(KafkaOffsetRange(KafkaOffset(100), KafkaOffset(99)).isEmpty)
    val r = KafkaOffsetRange(KafkaOffset(1), KafkaOffset(99)).get
    assert(r.distance == 98)
  }

  test("numOfRecordsSince") {
    val r = transientConsumer.numOfRecordsSince(NJTimestamp(100)).unsafeRunSync()
    val v = r.flatten
    assert(v.nonEmpty)
  }

  test("partitionsFor") {
    val r = transientConsumer.partitionsFor.unsafeRunSync()
    assert(r.value.nonEmpty)
  }

  test("retrieveRecordsForTimes") {
    val r = transientConsumer.retrieveRecordsForTimes(NJTimestamp(100)).unsafeRunSync()
    assert(r.nonEmpty)
  }

  test("commitSync") {
    transientConsumer
      .commitSync(Map(new TopicPartition("range.test", 0) -> new OffsetAndMetadata(0)))
      .unsafeRunSync()
  }
}
