package com.github.chenharryhua.nanjin.spark.kafka

import java.time.ZoneId

import com.github.chenharryhua.nanjin.datetime.NJDateTimeRange
import com.github.chenharryhua.nanjin.kafka.{KafkaTopic, TopicDef}
import com.github.chenharryhua.nanjin.messages.kafka.codec.AvroCodec
import com.github.chenharryhua.nanjin.spark.AvroTypedEncoder
import com.github.chenharryhua.nanjin.spark.persist.loaders
import frameless.{TypedDataset, TypedEncoder}
import io.circe.{Decoder => JsonDecoder}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

private[kafka] trait DatasetExtensions {

  implicit final class SparKafkaTopicSyntax[F[_], K, V](topic: KafkaTopic[F, K, V])
      extends Serializable {

    def sparKafka(cfg: SKConfig)(implicit ss: SparkSession): SparKafka[F, K, V] =
      new SparKafka(topic, ss, cfg)

    def sparKafka(zoneId: ZoneId)(implicit ss: SparkSession): SparKafka[F, K, V] =
      sparKafka(SKConfig(topic.topicDef.topicName, zoneId))

    def sparKafka(dtr: NJDateTimeRange)(implicit ss: SparkSession): SparKafka[F, K, V] =
      sparKafka(SKConfig(topic.topicDef.topicName, dtr))

    def sparKafka(implicit ss: SparkSession): SparKafka[F, K, V] =
      sparKafka(SKConfig(topic.topicDef.topicName, ZoneId.systemDefault()))

  }

  implicit final class TopicDefExt[K, V](topicDef: TopicDef[K, V]) extends Serializable {
    private val keyCodec: AvroCodec[K]             = topicDef.serdeOfKey.avroCodec
    private val valCodec: AvroCodec[V]             = topicDef.serdeOfVal.avroCodec
    val avroCodec: AvroCodec[OptionalKV[K, V]]     = OptionalKV.avroCodec(keyCodec, valCodec)
    val avroKCodec: AvroCodec[CompulsoryK[K, V]]   = CompulsoryK.avroCodec(keyCodec, valCodec)
    val avroVCodec: AvroCodec[CompulsoryV[K, V]]   = CompulsoryV.avroCodec(keyCodec, valCodec)
    val avroKVCodec: AvroCodec[CompulsoryKV[K, V]] = CompulsoryKV.avroCodec(keyCodec, valCodec)

    def ate(implicit
      keyEncoder: TypedEncoder[K],
      valEncoder: TypedEncoder[V]): AvroTypedEncoder[OptionalKV[K, V]] = {
      implicit val te: TypedEncoder[OptionalKV[K, V]] = shapeless.cachedImplicit
      AvroTypedEncoder[OptionalKV[K, V]](avroCodec)
    }

    def kate(implicit
      keyEncoder: TypedEncoder[K],
      valEncoder: TypedEncoder[V]): AvroTypedEncoder[CompulsoryK[K, V]] = {
      implicit val te: TypedEncoder[CompulsoryK[K, V]] = shapeless.cachedImplicit
      AvroTypedEncoder[CompulsoryK[K, V]](avroKCodec)
    }

    def vate(implicit
      keyEncoder: TypedEncoder[K],
      valEncoder: TypedEncoder[V]): AvroTypedEncoder[CompulsoryV[K, V]] = {
      implicit val te: TypedEncoder[CompulsoryV[K, V]] = shapeless.cachedImplicit
      AvroTypedEncoder[CompulsoryV[K, V]](avroVCodec)
    }

    def kvate(implicit
      keyEncoder: TypedEncoder[K],
      valEncoder: TypedEncoder[V]): AvroTypedEncoder[CompulsoryKV[K, V]] = {
      implicit val te: TypedEncoder[CompulsoryKV[K, V]] = shapeless.cachedImplicit
      AvroTypedEncoder[CompulsoryKV[K, V]](avroKVCodec)
    }

    object load {

      def avro(pathStr: String)(implicit
        keyEncoder: TypedEncoder[K],
        valEncoder: TypedEncoder[V],
        ss: SparkSession): TypedDataset[OptionalKV[K, V]] =
        loaders.avro[OptionalKV[K, V]](pathStr, ate)(ss)

      def parquet(pathStr: String)(implicit
        keyEncoder: TypedEncoder[K],
        valEncoder: TypedEncoder[V],
        ss: SparkSession): TypedDataset[OptionalKV[K, V]] =
        loaders.parquet[OptionalKV[K, V]](pathStr, ate)(ss)

      def json(pathStr: String)(implicit
        keyEncoder: TypedEncoder[K],
        valEncoder: TypedEncoder[V],
        ss: SparkSession): TypedDataset[OptionalKV[K, V]] =
        loaders.json[OptionalKV[K, V]](pathStr, ate)(ss)

      def jackson(pathStr: String)(implicit
        keyEncoder: TypedEncoder[K],
        valEncoder: TypedEncoder[V],
        ss: SparkSession): TypedDataset[OptionalKV[K, V]] =
        loaders.jackson[OptionalKV[K, V]](pathStr, ate)(ss)

      def binAvro(pathStr: String)(implicit
        keyEncoder: TypedEncoder[K],
        valEncoder: TypedEncoder[V],
        ss: SparkSession): TypedDataset[OptionalKV[K, V]] =
        loaders.binAvro[OptionalKV[K, V]](pathStr, ate)(ss)

      def circe(pathStr: String)(implicit
        keyEncoder: TypedEncoder[K],
        valEncoder: TypedEncoder[V],
        ev: JsonDecoder[OptionalKV[K, V]],
        ss: SparkSession): TypedDataset[OptionalKV[K, V]] =
        loaders.circe[OptionalKV[K, V]](pathStr, ate)

      def objectFile(pathStr: String)(implicit
        keyEncoder: TypedEncoder[K],
        valEncoder: TypedEncoder[V],
        ss: SparkSession): TypedDataset[OptionalKV[K, V]] =
        loaders.objectFile[OptionalKV[K, V]](pathStr, ate)

      object rdd {

        def avro(pathStr: String)(implicit ss: SparkSession): RDD[OptionalKV[K, V]] =
          loaders.rdd.avro[OptionalKV[K, V]](pathStr, avroCodec.avroDecoder)

        def jackson(pathStr: String)(implicit ss: SparkSession): RDD[OptionalKV[K, V]] =
          loaders.rdd.jackson[OptionalKV[K, V]](pathStr, avroCodec.avroDecoder)

        def binAvro(pathStr: String)(implicit ss: SparkSession): RDD[OptionalKV[K, V]] =
          loaders.rdd.binAvro[OptionalKV[K, V]](pathStr, avroCodec.avroDecoder)

        def circe(pathStr: String)(implicit
          ev: JsonDecoder[OptionalKV[K, V]],
          ss: SparkSession): RDD[OptionalKV[K, V]] =
          loaders.rdd.circe[OptionalKV[K, V]](pathStr)

        def objectFile(pathStr: String)(implicit ss: SparkSession): RDD[OptionalKV[K, V]] =
          loaders.rdd.objectFile[OptionalKV[K, V]](pathStr)

      }
    }
  }
}
