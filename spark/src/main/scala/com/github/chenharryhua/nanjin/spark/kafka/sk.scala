package com.github.chenharryhua.nanjin.spark.kafka

import java.util

import cats.effect.{ConcurrentEffect, ContextShift, Sync, Timer}
import cats.implicits._
import com.github.chenharryhua.nanjin.common.NJFileFormat
import com.github.chenharryhua.nanjin.datetime.NJDateTimeRange
import com.github.chenharryhua.nanjin.kafka.common._
import com.github.chenharryhua.nanjin.kafka.{KafkaConsumerApi, KafkaTopicKit}
import com.github.chenharryhua.nanjin.utils.Keyboard
import frameless.{TypedDataset, TypedEncoder}
import fs2.Pipe
import fs2.kafka.{produce, ProducerRecords, ProducerResult}
import io.circe.syntax._
import monocle.function.At.remove
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.apache.spark.streaming.kafka010.{KafkaUtils, LocationStrategy, OffsetRange}
import org.log4s.Logger

import scala.collection.JavaConverters._

object sk {

  private def props(config: Map[String, String]): util.Map[String, Object] =
    (remove(ConsumerConfig.CLIENT_ID_CONFIG)(config) ++ Map(
      "key.deserializer" -> classOf[ByteArrayDeserializer].getName,
      "value.deserializer" -> classOf[ByteArrayDeserializer].getName))
      .mapValues[Object](identity)
      .asJava

  private def offsetRanges(
    range: KafkaTopicPartition[Option[KafkaOffsetRange]]): Array[OffsetRange] =
    range.flatten[KafkaOffsetRange].value.toArray.map {
      case (tp, r) => OffsetRange.create(tp, r.from.value, r.until.value)
    }

  private def kafkaRDD[F[_]: Sync, K, V](
    kit: KafkaTopicKit[K, V],
    timeRange: NJDateTimeRange,
    locationStrategy: LocationStrategy)(
    implicit sparkSession: SparkSession): F[RDD[ConsumerRecord[Array[Byte], Array[Byte]]]] =
    KafkaConsumerApi(kit).use(_.offsetRangeFor(timeRange)).map { gtp =>
      KafkaUtils.createRDD[Array[Byte], Array[Byte]](
        sparkSession.sparkContext,
        props(kit.settings.consumerSettings.config),
        offsetRanges(gtp),
        locationStrategy)
    }

  private val logger: Logger = org.log4s.getLogger("spark.kafka")

  private def decode[K, V](
    kit: KafkaTopicKit[K, V],
    iterator: Iterator[ConsumerRecord[Array[Byte], Array[Byte]]])
    : Iterator[NJConsumerRecord[K, V]] =
    iterator.map { m =>
      val (errs, cr) = kit.decoder(m).logRecord.run
      errs.map(x => logger.warn(x.error)(x.metaInfo))
      cr
    }

  def loadKafkaRdd[F[_]: Sync, K, V, A](
    kit: KafkaTopicKit[K, V],
    timeRange: NJDateTimeRange,
    locationStrategy: LocationStrategy)(
    implicit
    sparkSession: SparkSession): F[RDD[NJConsumerRecord[K, V]]] =
    kafkaRDD[F, K, V](kit, timeRange, locationStrategy).map(_.mapPartitions(ms => decode(kit, ms)))

  def loadDiskRdd[F[_]: Sync, K, V](path: String)(
    implicit sparkSession: SparkSession): F[RDD[NJConsumerRecord[K, V]]] =
    Sync[F].delay(sparkSession.sparkContext.objectFile[NJConsumerRecord[K, V]](path))

  def save[K, V](
    dataset: TypedDataset[NJConsumerRecord[K, V]],
    kit: KafkaTopicKit[K, V],
    fileFormat: NJFileFormat,
    saveMode: SaveMode,
    path: String): Unit =
    fileFormat match {
      case NJFileFormat.Avro | NJFileFormat.Parquet | NJFileFormat.Json | NJFileFormat.Text =>
        dataset.write.mode(saveMode).format(fileFormat.format).save(path)
      case NJFileFormat.Jackson =>
        dataset.deserialized
          .map(m => kit.topicDef.toJackson(m).noSpaces)
          .write
          .mode(saveMode)
          .text(path)
    }

  def uploader[F[_]: ConcurrentEffect: ContextShift: Timer, K, V](
    kit: KafkaTopicKit[K, V],
    uploadRate: NJUploadRate): Pipe[F, NJProducerRecord[K, V], ProducerResult[K, V, Unit]] =
    njPRs =>
      for {
        kb <- Keyboard.signal[F]
        rst <- njPRs
          .pauseWhen(kb.map(_.contains(Keyboard.pauSe)))
          .interruptWhen(kb.map(_.contains(Keyboard.Quit)))
          .chunkN(uploadRate.batchSize)
          .metered(uploadRate.duration)
          .map(chk => ProducerRecords(chk.map(_.toFs2ProducerRecord(kit.topicName))))
          .through(produce(kit.fs2ProducerSettings[F]))
      } yield rst

  /**
    * streaming
    */
  private def startingOffsets(range: KafkaTopicPartition[Option[KafkaOffsetRange]]): String = {
    val start: Map[String, Map[String, Long]] = range
      .flatten[KafkaOffsetRange]
      .value
      .map { case (tp, kor) => (tp.topic(), tp.partition(), kor) }
      .foldLeft(Map.empty[String, Map[String, Long]]) {
        case (sum, item) =>
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

    rm1.andThen(rm2).andThen(rm3).andThen(rm4).andThen(rm5).andThen(rm6)(m).map {
      case (k, v) => s"kafka.$k" -> v
    }
  }

  private def decodeSparkStreamCR[K, V](kit: KafkaTopicKit[K, V])
    : NJConsumerRecord[Array[Byte], Array[Byte]] => NJConsumerRecord[K, V] =
    rawCr => {
      val smi = ShowMetaInfo[NJConsumerRecord[Array[Byte], Array[Byte]]]
      val cr  = NJConsumerRecord.timestamp.set(rawCr.timestamp / 1000)(rawCr) //spark use micro-second.
      cr.bimap(
          k =>
            kit.codec.keyCodec
              .tryDecode(k)
              .toEither
              .leftMap(logger.warn(_)(s"key decode error. ${smi.metaInfo(cr)}"))
              .toOption,
          v =>
            kit.codec.valCodec
              .tryDecode(v)
              .toEither
              .leftMap(logger.warn(_)(s"value decode error. ${smi.metaInfo(cr)}"))
              .toOption
        )
        .flatten[K, V]
    }

  def streaming[F[_]: Sync, K, V, A](kit: KafkaTopicKit[K, V], timeRange: NJDateTimeRange)(
    f: NJConsumerRecord[K, V] => A)(
    implicit
    sparkSession: SparkSession,
    encoder: TypedEncoder[A]): F[TypedDataset[A]] = {

    import sparkSession.implicits._
    KafkaConsumerApi(kit).use(_.offsetRangeFor(timeRange)).map { gtp =>
      TypedDataset
        .create(
          sparkSession.readStream
            .format("kafka")
            .options(consumerOptions(kit.settings.consumerSettings.config))
            .option("startingOffsets", startingOffsets(gtp))
            .option("subscribe", kit.topicDef.topicName.value)
            .load()
            .as[NJConsumerRecord[Array[Byte], Array[Byte]]])
        .deserialized
        .mapPartitions(_.map(decodeSparkStreamCR(kit).andThen(f)))
    }
  }
}
