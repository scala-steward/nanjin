package com.github.chenharryhua.nanjin.spark.kafka

import cats.effect.Sync
import cats.implicits._
import com.github.chenharryhua.nanjin.common.NJFileFormat
import com.github.chenharryhua.nanjin.messages.kafka.OptionalKV
import com.github.chenharryhua.nanjin.spark.SparkSessionExt
import frameless.TypedEncoder
import frameless.cats.implicits.framelessCatsSparkDelayForSync
import io.circe.generic.auto._
import io.circe.{Decoder => JsonDecoder}

private[kafka] trait ReadOps[F[_], K, V] { self: SparKafka[F, K, V] =>
  import self.topic.topicDef._

  final def fromKafka(implicit sync: Sync[F]): F[CrRdd[F, K, V]] =
    sk.unloadKafka(topic, params.timeRange, params.locationStrategy)
      .map(new CrRdd[F, K, V](_, topic.topicName, cfg))

  final def fromDisk: CrRdd[F, K, V] =
    new CrRdd[F, K, V](
      sk.loadDiskRdd[K, V](params.replayPath(topic.topicName)),
      topic.topicName,
      cfg)

  // avro
  final def readAvro(pathStr: String)(implicit
    keyEncoder: TypedEncoder[K],
    valEncoder: TypedEncoder[V]): CrRdd[F, K, V] =
    new CrRdd[F, K, V](
      sparkSession.avro[OptionalKV[K, V]](pathStr).dataset.rdd,
      topic.topicName,
      cfg)

  final def readAvro(implicit
    keyEncoder: TypedEncoder[K],
    valEncoder: TypedEncoder[V]): CrRdd[F, K, V] =
    readAvro(params.pathBuilder(topic.topicName, NJFileFormat.Avro))

  // parquet
  final def readParquet(pathStr: String)(implicit
    keyEncoder: TypedEncoder[K],
    valEncoder: TypedEncoder[V]): CrRdd[F, K, V] =
    new CrRdd[F, K, V](
      sparkSession.parquet[OptionalKV[K, V]](pathStr).dataset.rdd,
      topic.topicName,
      cfg)

  final def readParquet(implicit
    keyEncoder: TypedEncoder[K],
    valEncoder: TypedEncoder[V]): CrRdd[F, K, V] =
    readParquet(params.pathBuilder(topic.topicName, NJFileFormat.Parquet))

  // json
  final def readJson(pathStr: String)(implicit
    jsonKeyDecoder: JsonDecoder[K],
    jsonValDecoder: JsonDecoder[V]): CrRdd[F, K, V] =
    new CrRdd[F, K, V](sparkSession.json[OptionalKV[K, V]](pathStr), topic.topicName, cfg)

  final def readJson(implicit
    jsonKeyDecoder: JsonDecoder[K],
    jsonValDecoder: JsonDecoder[V]): CrRdd[F, K, V] =
    readJson(params.pathBuilder(topic.topicName, NJFileFormat.Json))

  // jackson
  final def readJackson(pathStr: String): CrRdd[F, K, V] =
    new CrRdd[F, K, V](sparkSession.jackson[OptionalKV[K, V]](pathStr), topic.topicName, cfg)

  final def readJackson: CrRdd[F, K, V] =
    readJackson(params.pathBuilder(topic.topicName, NJFileFormat.Jackson))

}
