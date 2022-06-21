package com.github.chenharryhua.nanjin.spark.kafka

import cats.effect.IO
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.common.kafka.TopicName
import com.github.chenharryhua.nanjin.kafka.TopicDef
import com.github.chenharryhua.nanjin.messages.kafka.NJConsumerRecord
import com.github.chenharryhua.nanjin.spark.{AvroTypedEncoder, SparkSessionExt}
import mtest.spark.kafka.sparKafka
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

class SortTest extends AnyFunSuite {
  val topic = TopicDef[Int, Int](TopicName("topic"))
  val ate   = AvroTypedEncoder(topic)

  val data = List(
    NJConsumerRecord[Int, Int](0, 0, 40, Some(0), Some(Random.nextInt()), "topic", 0),
    NJConsumerRecord[Int, Int](0, 1, 30, Some(0), Some(Random.nextInt()), "topic", 0),
    NJConsumerRecord[Int, Int](0, 2, 20, Some(0), Some(Random.nextInt()), "topic", 0),
    NJConsumerRecord[Int, Int](0, 3, 10, Some(0), Some(Random.nextInt()), "topic", 0),
    NJConsumerRecord[Int, Int](1, 0, 40, Some(1), Some(Random.nextInt()), "topic", 0),
    NJConsumerRecord[Int, Int](1, 1, 20, Some(1), Some(Random.nextInt()), "topic", 0),
    NJConsumerRecord[Int, Int](1, 2, 20, Some(1), Some(Random.nextInt()), "topic", 0),
    NJConsumerRecord[Int, Int](1, 4, 50, Some(2), Some(Random.nextInt()), "topic", 0),
    NJConsumerRecord[Int, Int](2, 100, 100, Some(2), Some(Random.nextInt()), "topic", 0),
    NJConsumerRecord[Int, Int](2, 100, 100, Some(2), Some(Random.nextInt()), "topic", 0)
  )
  val rdd   = sparKafka.sparkSession.sparkContext.parallelize(data)
  val crRdd = sparKafka.topic(topic).crRdd(rdd)
  val prRdd = crRdd.prRdd

  val njDataset = sparKafka.sparkSession.loadTable[IO](ate).data(rdd).dataset

  test("produce record") {
    assert(
      prRdd.ascendTimestamp.rdd.collect().toList.map(_.key) == crRdd.ascendTimestamp.rdd
        .collect()
        .toList
        .map(_.key))

    assert(
      prRdd.ascendOffset.rdd.collect().toList.map(_.key) == crRdd.ascendOffset.rdd
        .collect()
        .toList
        .map(_.key))
    assert(
      prRdd.descendTimestamp.rdd.collect().toList.map(_.key) == crRdd.descendTimestamp.rdd
        .collect()
        .toList
        .map(_.key))

    assert(
      prRdd.descendOffset.rdd.collect().toList.map(_.key) == crRdd.descendOffset.rdd
        .collect()
        .toList
        .map(_.key))
  }
  test("disorders") {
    assert(crRdd.stats.disorders.count() == 4)
  }
  test("dup") {
    assert(crRdd.stats.dupRecords.count() == 1)
  }
  test("missing offsets") {

    assert(crRdd.stats.missingOffsets.count() == 1)
  }

  test("misorder keys") {
    import com.github.chenharryhua.nanjin.spark.kafka.functions.NJConsumerRecordDatasetExt
    assert(njDataset.misorderedKey.count() == 4)
  }

  test("misplaced keys") {
    import com.github.chenharryhua.nanjin.spark.kafka.functions.NJConsumerRecordDatasetExt
    assert(njDataset.misplacedKey.count() == 1)
  }
}
