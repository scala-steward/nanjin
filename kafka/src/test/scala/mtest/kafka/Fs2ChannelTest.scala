package mtest.kafka

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.toBifunctorOps
import com.github.chenharryhua.nanjin.common.kafka.TopicName
import com.github.chenharryhua.nanjin.kafka.*
import com.github.chenharryhua.nanjin.messages.kafka.NJConsumerRecord
import com.github.chenharryhua.nanjin.messages.kafka.codec.gr2Jackson
import eu.timepit.refined.auto.*
import io.circe.generic.auto.*
import io.circe.syntax.EncoderOps
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.*

object Fs2ChannelTestData {
  final case class Fs2Kafka(a: Int, b: String, c: Double)
  val topicDef: TopicDef[Int, Fs2Kafka]    = TopicDef[Int, Fs2Kafka](TopicName("fs2.kafka.test"))
  val topic: KafkaTopic[IO, Int, Fs2Kafka] = ctx.topic(topicDef)
  val jackson =
    """
      {
      "partition" : 0,
      "offset" : 0,
      "timestamp" : 1696207641300,
      "key" : {
        "int" : 1
      },
      "value" : {
        "mtest.kafka.Fs2ChannelTestData.Fs2Kafka" : {
          "a" : 1,
          "b" : "a",
          "c" : 1.0
        }
      },
      "topic" : "whatever",
      "timestampType" : 0,
      "headers" : [
      ]
    }
     """

  val json =
    """
      {
      "partition" : 0,
      "offset" : 0,
      "timestamp" : 1696207641300,
      "key" : 1,
      "value" : {
        "a" : 1,
        "b" : "a",
        "c" : 1.0
      },
      "topic" : "don't care",
      "timestampType" : 0,
      "headers" : [
      ]
    } """
}

class Fs2ChannelTest extends AnyFunSuite {
  import Fs2ChannelTestData.*
  test("should be able to consume avro topic") {

    val ret =
      ctx.schemaRegistry.register(topicDef).attempt >>
        topic.produceOne(1, Fs2Kafka(1, "a", 1.0)) >>
        topic.consume
          .updateConfig(_.withGroupId("g1"))
          .stream
          .take(1)
          .map(ccr => NJConsumerRecord(ccr.record).asJson)
          .timeout(3.seconds)
          .compile
          .toList
    assert(ret.unsafeRunSync().size == 1)
  }

  test("record format") {
    val ret =
      topic.consume.stream
        .take(1)
        .map(_.record)
        .map(r => gr2Jackson(topic.topicDef.consumerFormat.toRecord(r)).get)
        .timeout(3.seconds)
        .compile
        .toList
        .unsafeRunSync()
    assert(ret.size == 1)
  }

  test("serde") {
    ctx
      .consume(topic.topicName)
      .stream
      .take(1)
      .map { ccr =>
        topic.serde.deserialize(ccr)
        topic.serde.deserializeKey(ccr)
        topic.serde.deserializeValue(ccr.record)
        topic.serde.tryDeserialize(ccr.record)
        topic.serde.tryDeserializeKey(ccr.record)
        topic.serde.tryDeserializeValue(ccr.record)
        topic.serde.tryDeserializeKeyValue(ccr.record)
        topic.serde.nullableDeserialize(ccr.record)
        topic.serde.nullableDeserialize(ccr.record.bimap[Array[Byte], Array[Byte]](_ => null, _ => null))
        val nj = topic.serde.toNJConsumerRecord(ccr).toNJProducerRecord.toProducerRecord
        topic.serde.serialize(nj)
      }
      .timeout(3.seconds)
      .compile
      .toList
      .unsafeRunSync()
  }

  test("circe") {
    topic.produceCirce(json).unsafeRunSync()
  }

  test("jackson") {
    topic.produceJackson(jackson).unsafeRunSync()
  }

  test("produce") {
    val jackson =
      """
      {
      "partition" : 0,
      "offset" : 0,
      "timestamp" : 1696207641300,
      "key" : {
        "int" : 2
      },
      "value" : {
        "mtest.kafka.Fs2ChannelTestData.Fs2Kafka" : {
          "a" : 2,
          "b" : "b",
          "c" : 2.0
        }
      },
      "serializedKeySize":null,
      "serializedValueSize":null,
      "topic" : "fs2.kafka.test",
      "timestampType" : 0,
      "headers" : [
      ],
      "leaderEpoch":null
    }
     """
    ctx.produce(jackson).flatMap(IO.println).unsafeRunSync()
  }
}
