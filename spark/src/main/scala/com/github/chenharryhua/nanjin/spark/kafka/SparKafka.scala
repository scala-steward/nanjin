package com.github.chenharryhua.nanjin.spark.kafka

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Sync, Timer}
import cats.syntax.all._
import com.github.chenharryhua.nanjin.common.UpdateParams
import com.github.chenharryhua.nanjin.kafka.KafkaTopic
import com.github.chenharryhua.nanjin.messages.kafka.codec.AvroCodec
import com.github.chenharryhua.nanjin.spark.persist.loaders
import com.github.chenharryhua.nanjin.spark.sstream.{KafkaCrSStream, SStreamConfig, SparkSStream}
import com.github.chenharryhua.nanjin.spark.{fileSink, AvroTypedEncoder}
import frameless.cats.implicits.framelessCatsSparkDelayForSync
import frameless.{TypedDataset, TypedEncoder}
import io.circe.{Decoder => JsonDecoder}
import org.apache.avro.Schema
import org.apache.parquet.avro.AvroSchemaConverter
import org.apache.parquet.schema.MessageType
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.avro.SchemaConverters
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.streaming.StreamingContext

trait SparKafkaUpdateParams[A] extends UpdateParams[SKConfig, A] with Serializable {
  def params: SKParams
}

final class SparKafka[F[_], K, V](
  val topic: KafkaTopic[F, K, V],
  val sparkSession: SparkSession,
  val cfg: SKConfig
) extends SparKafkaUpdateParams[SparKafka[F, K, V]] {

  val codec: KafkaAvroCodec[K, V] =
    new KafkaAvroCodec[K, V](
      topic.topicDef.serdeOfKey.avroCodec,
      topic.topicDef.serdeOfVal.avroCodec)

  implicit val ss: SparkSession = sparkSession

  override def withParamUpdate(f: SKConfig => SKConfig): SparKafka[F, K, V] =
    new SparKafka[F, K, V](topic, sparkSession, f(cfg))

  override val params: SKParams = cfg.evalConfig

  def avroSchema: Schema         = codec.schemaForOptionalKV.schema
  def sparkSchema: DataType      = SchemaConverters.toSqlType(avroSchema).dataType
  def parquetSchema: MessageType = new AvroSchemaConverter().convert(avroSchema)

  def fromKafka(implicit sync: Sync[F]): F[CrRdd[F, K, V]] =
    sk.kafkaBatch(topic, params.timeRange, params.locationStrategy).map(crRdd)

  def fromDisk: CrRdd[F, K, V] =
    crRdd(loaders.objectFile(params.replayPath))

  /**
    * shorthand
    */
  def dump(implicit F: Sync[F], cs: ContextShift[F]): F[Long] =
    Blocker[F].use(blocker =>
      for {
        _ <- fileSink[F](blocker).delete(params.replayPath)
        cr <- fromKafka
      } yield {
        cr.rdd.saveAsObjectFile(params.replayPath)
        cr.rdd.count
      })

  def replay(implicit ce: ConcurrentEffect[F], timer: Timer[F], cs: ContextShift[F]): F[Unit] =
    fromDisk.pipeTo(topic)

  def countKafka(implicit F: Sync[F]): F[Long] = fromKafka.flatMap(_.count)
  def countDisk(implicit F: Sync[F]): F[Long]  = fromDisk.count

  def pipeTo(other: KafkaTopic[F, K, V])(implicit
    ce: ConcurrentEffect[F],
    timer: Timer[F],
    cs: ContextShift[F]): F[Unit] =
    fromKafka.flatMap(_.pipeTo(other))

  /**
    * rdd and dataset
    */
  def crRdd(rdd: RDD[OptionalKV[K, V]]) =
    new CrRdd[F, K, V](rdd, codec, cfg)

  def crDataset(tds: TypedDataset[OptionalKV[K, V]])(implicit
    keyEncoder: TypedEncoder[K],
    valEncoder: TypedEncoder[V]): CrDataset[F, K, V] = {
    val c = new KafkaAvroTypedEncoder[K, V](keyEncoder, valEncoder, codec)
    new CrDataset[F, K, V](tds.dataset, c, cfg)
  }

  def crDataset(ds: Dataset[OptionalKV[K, V]])(implicit
    keyEncoder: TypedEncoder[K],
    valEncoder: TypedEncoder[V]): CrDataset[F, K, V] = {
    val c = new KafkaAvroTypedEncoder[K, V](keyEncoder, valEncoder, codec)
    new CrDataset[F, K, V](ds, c, cfg)
  }

  def prDataset(tds: TypedDataset[NJProducerRecord[K, V]])(implicit
    keyEncoder: TypedEncoder[K],
    valEncoder: TypedEncoder[V]): PrDataset[F, K, V] = {
    val kate = new KafkaAvroTypedEncoder[K, V](keyEncoder, valEncoder, codec)
    new PrDataset[F, K, V](tds.dataset, kate, cfg)
  }

  /**
    * direct stream
    */

  def dstream(sc: StreamingContext): CrDStream[F, K, V] =
    new CrDStream[F, K, V](sk.kafkaDStream[F, K, V](topic, sc, params.locationStrategy), cfg)

  object load {

    object tds {

      def avro(pathStr: String)(implicit
        keyEncoder: TypedEncoder[K],
        valEncoder: TypedEncoder[V]): CrDataset[F, K, V] = {
        val ate = AvroTypedEncoder[OptionalKV[K, V]](codec.optionalKVCodec)
        crDataset(loaders.avro(pathStr)(ate, sparkSession))
      }

      def parquet(pathStr: String)(implicit
        keyEncoder: TypedEncoder[K],
        valEncoder: TypedEncoder[V]): CrDataset[F, K, V] = {
        val ate = AvroTypedEncoder[OptionalKV[K, V]](codec.optionalKVCodec)
        crDataset(loaders.parquet(pathStr)(ate, sparkSession))
      }

      def json(pathStr: String)(implicit
        keyEncoder: TypedEncoder[K],
        valEncoder: TypedEncoder[V]): CrDataset[F, K, V] = {
        val ate = AvroTypedEncoder[OptionalKV[K, V]](codec.optionalKVCodec)
        crDataset(loaders.json(pathStr)(ate, sparkSession))
      }
    }

    object rdd {
      implicit private val optionalKV: AvroCodec[OptionalKV[K, V]] = codec.optionalKVCodec

      def avro(pathStr: String): CrRdd[F, K, V] =
        crRdd(loaders.raw.avro[OptionalKV[K, V]](pathStr))

      def parquet(pathStr: String): CrRdd[F, K, V] =
        crRdd(loaders.raw.parquet[OptionalKV[K, V]](pathStr))

      def jackson(pathStr: String): CrRdd[F, K, V] =
        crRdd(loaders.raw.jackson[OptionalKV[K, V]](pathStr))

      def binAvro(pathStr: String): CrRdd[F, K, V] =
        crRdd(loaders.raw.binAvro[OptionalKV[K, V]](pathStr))

      def circe(pathStr: String)(implicit ev: JsonDecoder[OptionalKV[K, V]]): CrRdd[F, K, V] =
        crRdd(loaders.circe[OptionalKV[K, V]](pathStr))

    }
  }

  /**
    * structured stream
    */

  def sstream[A](f: OptionalKV[K, V] => A)(implicit
    sync: Sync[F],
    encoder: TypedEncoder[A]): SparkSStream[F, A] =
    new SparkSStream[F, A](
      sk.kafkaSStream[F, K, V, A](topic)(f).dataset,
      SStreamConfig(params.timeRange, params.showDs)
        .withCheckpointAppend(s"kafka/${topic.topicName.value}"))

  def sstream(implicit
    sync: Sync[F],
    keyEncoder: TypedEncoder[K],
    valEncoder: TypedEncoder[V]): KafkaCrSStream[F, K, V] =
    new KafkaCrSStream[F, K, V](
      sk.kafkaSStream[F, K, V, OptionalKV[K, V]](topic)(identity).dataset,
      SStreamConfig(params.timeRange, params.showDs)
        .withCheckpointAppend(s"kafkacr/${topic.topicName.value}"))
}
