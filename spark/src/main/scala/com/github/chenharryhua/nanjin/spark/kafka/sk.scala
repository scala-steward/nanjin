package com.github.chenharryhua.nanjin.spark.kafka

import cats.data.{Chain, Writer}
import cats.effect.{ConcurrentEffect, ContextShift, Sync, Timer}
import cats.mtl.Tell
import cats.syntax.all._
import com.github.chenharryhua.nanjin.datetime.NJDateTimeRange
import com.github.chenharryhua.nanjin.kafka.{KafkaOffsetRange, KafkaTopic, KafkaTopicPartition}
import com.github.chenharryhua.nanjin.spark.AvroTypedEncoder
import com.github.chenharryhua.nanjin.utils.Keyboard
import fs2.Pipe
import fs2.kafka.{produce, ProducerRecords, ProducerResult}
import io.circe.syntax._
import monocle.function.At.remove
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka010.{
  ConsumerStrategies,
  KafkaUtils,
  LocationStrategy,
  OffsetRange
}
import org.log4s.Logger

import java.util
import scala.collection.JavaConverters._

private[kafka] object sk {

  implicit val tell: Tell[Writer[Chain[Throwable], *], Chain[Throwable]] =
    shapeless.cachedImplicit

  private def props(config: Map[String, String]): util.Map[String, Object] =
    (remove(ConsumerConfig.CLIENT_ID_CONFIG)(config) ++ Map(
      "key.deserializer" -> classOf[ByteArrayDeserializer].getName,
      "value.deserializer" -> classOf[ByteArrayDeserializer].getName))
      .mapValues[Object](identity)
      .asJava

  private def offsetRanges(
    range: KafkaTopicPartition[Option[KafkaOffsetRange]]): Array[OffsetRange] =
    range.flatten[KafkaOffsetRange].value.toArray.map { case (tp, r) =>
      OffsetRange.create(tp, r.from.value, r.until.value)
    }

  private def kafkaRDD[F[_]: Sync, K, V](
    topic: KafkaTopic[F, K, V],
    timeRange: NJDateTimeRange,
    locationStrategy: LocationStrategy)(implicit
    sparkSession: SparkSession): F[RDD[ConsumerRecord[Array[Byte], Array[Byte]]]] =
    topic.shortLiveConsumer.use(_.offsetRangeFor(timeRange)).map { gtp =>
      KafkaUtils.createRDD[Array[Byte], Array[Byte]](
        sparkSession.sparkContext,
        props(topic.context.settings.consumerSettings.config),
        offsetRanges(gtp),
        locationStrategy)
    }

  private val logger: Logger = org.log4s.getLogger("nj.spark.kafka")

  def kafkaDStream[F[_], K, V](
    topic: KafkaTopic[F, K, V],
    streamingContext: StreamingContext,
    locationStrategy: LocationStrategy): DStream[OptionalKV[K, V]] = {

    val consumerStrategy =
      ConsumerStrategies.Subscribe[Array[Byte], Array[Byte]](
        List(topic.topicName.value),
        props(topic.context.settings.consumerSettings.config).asScala)
    KafkaUtils
      .createDirectStream(streamingContext, locationStrategy, consumerStrategy)
      .mapPartitions { ms =>
        val decoder = new NJConsumerRecordDecoder[Writer[Chain[Throwable], *], K, V](
          topic.topicName.value,
          topic.codec.keyDeserializer,
          topic.codec.valDeserializer)
        ms.map { m =>
          val (errs, cr) = decoder.decode(m).run
          errs.toList.foreach(err => logger.warn(err)(s"decode error: ${cr.metaInfo}"))
          cr
        }
      }
  }

  def kafkaBatch[F[_]: Sync, K, V](
    topic: KafkaTopic[F, K, V],
    timeRange: NJDateTimeRange,
    locationStrategy: LocationStrategy)(implicit
    sparkSession: SparkSession): F[RDD[OptionalKV[K, V]]] =
    kafkaRDD[F, K, V](topic, timeRange, locationStrategy).map(_.mapPartitions { ms =>
      val decoder = new NJConsumerRecordDecoder[Writer[Chain[Throwable], *], K, V](
        topic.topicName.value,
        topic.codec.keyDeserializer,
        topic.codec.valDeserializer)
      ms.map { m =>
        val (errs, cr) = decoder.decode(m).run
        errs.toList.foreach(err => logger.warn(err)(s"decode error: ${cr.metaInfo}"))
        cr
      }
    })

  def uploader[F[_]: ConcurrentEffect: ContextShift: Timer, K, V](
    topic: KafkaTopic[F, K, V],
    uploadParams: NJUploadParams): Pipe[F, NJProducerRecord[K, V], ProducerResult[K, V, Unit]] =
    njPRs =>
      for {
        kb <- Keyboard.signal[F]
        rst <-
          njPRs
            .pauseWhen(kb.map(_.contains(Keyboard.pauSe)))
            .interruptWhen(kb.map(_.contains(Keyboard.Quit)))
            .interruptAfter(uploadParams.timeLimit)
            .take(uploadParams.recordsLimit)
            .chunkN(uploadParams.batchSize)
            .metered(uploadParams.duration)
            .map(chk => ProducerRecords(chk.map(_.toFs2ProducerRecord(topic.topicName.value))))
            .through(produce(topic.fs2ProducerSettings))
      } yield rst

  /** streaming
    */
  private def startingOffsets(range: KafkaTopicPartition[Option[KafkaOffsetRange]]): String = {
    val start: Map[String, Map[String, Long]] = range
      .flatten[KafkaOffsetRange]
      .value
      .map { case (tp, kor) => (tp.topic(), tp.partition(), kor) }
      .foldLeft(Map.empty[String, Map[String, Long]]) { case (sum, item) =>
        val rst = Map(item._2.toString -> item._3.from.value)
        sum.get(item._1) match {
          case Some(m) => Map(item._1 -> (m ++ rst))
          case None    => Map(item._1 -> rst)
        }
      }
    start.asJson.noSpaces
  }

  //  https://spark.apache.org/docs/2.4.5/structured-streaming-kafka-integration.html
  private def consumerOptions(m: Map[String, String]): Map[String, String] = {
    val rm1 = remove(ConsumerConfig.GROUP_ID_CONFIG)(_: Map[String, String])
    val rm2 = remove(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)(_: Map[String, String])
    val rm3 = remove(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)(_: Map[String, String])
    val rm4 = remove(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG)(_: Map[String, String])
    val rm5 = remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)(_: Map[String, String])
    val rm6 = remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)(_: Map[String, String])

    rm1.andThen(rm2).andThen(rm3).andThen(rm4).andThen(rm5).andThen(rm6)(m).map { case (k, v) =>
      s"kafka.$k" -> v
    }
  }

  def kafkaSStream[F[_]: Sync, K, V, A](topic: KafkaTopic[F, K, V], ate: AvroTypedEncoder[A])(
    f: OptionalKV[K, V] => A)(implicit sparkSession: SparkSession): Dataset[A] = {
    import sparkSession.implicits._

    sparkSession.readStream
      .format("kafka")
      .options(consumerOptions(topic.context.settings.consumerSettings.config))
      .option("subscribe", topic.topicDef.topicName.value)
      .load()
      .as[OptionalKV[Array[Byte], Array[Byte]]]
      .mapPartitions { ms =>
        val decoder = new NJConsumerRecordDecoder[Writer[Chain[Throwable], *], K, V](
          topic.topicName.value,
          topic.codec.keyDeserializer,
          topic.codec.valDeserializer)
        ms.map { cr =>
          val (errs, msg) = decoder.decode(cr).run
          errs.toList.foreach(err => logger.warn(err)(s"decode error: ${cr.metaInfo}"))
          f(OptionalKV.timestamp.modify(_ / 1000)(msg)) // spark use micro-second.
        }
      }(ate.sparkEncoder)
  }
}
