package com.github.chenharryhua.nanjin.spark.kafka

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Sync, Timer}
import cats.implicits._
import com.github.chenharryhua.nanjin.kafka.KafkaTopicKit
import com.github.chenharryhua.nanjin.kafka.common.NJConsumerRecord
import com.github.chenharryhua.nanjin.pipes.hadoop
import com.github.chenharryhua.nanjin.spark.{fileSink, RddExt}
import frameless.{TypedDataset, TypedEncoder}
import fs2.Stream
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

final class FsmRddKafka[F[_], K, V](
  rdd: RDD[NJConsumerRecord[K, V]],
  kit: KafkaTopicKit[K, V],
  cfg: SKConfig)(implicit sparkSession: SparkSession)
    extends SparKafkaUpdateParams[FsmRddKafka[F, K, V]] {
  override def params: SKParams = SKConfigF.evalConfig(cfg)

  override def withParamUpdate(f: SKConfig => SKConfig): FsmRddKafka[F, K, V] =
    new FsmRddKafka[F, K, V](rdd, kit, f(cfg))

  def save(implicit F: Sync[F], cs: ContextShift[F]): F[Unit] =
    Blocker[F].use { blocker =>
      val path = sk.replayPath(kit.topicName)
      hadoop.delete(path, sparkSession.sparkContext.hadoopConfiguration, blocker) >>
        F.delay(rdd.saveAsObjectFile(path))
    }

  def saveJackson(implicit F: Sync[F], cs: ContextShift[F]): F[Unit] = {
    import kit.topicDef.{avroKeyEncoder, avroValEncoder, schemaForKey, schemaForVal}
    sorted
      .stream[F]
      .through(fileSink[F].jackson[NJConsumerRecord[K, V]](sk.jacksonPath(kit.topicName)))
      .compile
      .drain
  }

  def saveAvro(implicit F: Sync[F], cs: ContextShift[F]): F[Unit] = {
    import kit.topicDef.{avroKeyEncoder, avroValEncoder}
    sorted
      .stream[F]
      .through(
        fileSink[F]
          .avro[NJConsumerRecord[K, V]](sk.jacksonPath(kit.topicName), kit.topicDef.njSchema))
      .compile
      .drain
  }

  def count: Long = rdd.count()

  def crDataset(
    implicit
    keyEncoder: TypedEncoder[K],
    valEncoder: TypedEncoder[V]): FsmConsumerRecords[F, K, V] =
    new FsmConsumerRecords(TypedDataset.create(rdd).dataset, kit, cfg)

  def partition(num: Int): FsmRddDisk[F, K, V] =
    new FsmRddDisk[F, K, V](rdd.filter(_.partition === num), kit, cfg)

  def sorted: RDD[NJConsumerRecord[K, V]] =
    rdd.repartition(params.repartition.value).sortBy[NJConsumerRecord[K, V]](identity)

  def crStream(implicit F: Sync[F]): Stream[F, NJConsumerRecord[K, V]] =
    sorted.stream[F]

  def pipeTo(otherTopic: KafkaTopicKit[K, V])(
    implicit
    concurrentEffect: ConcurrentEffect[F],
    timer: Timer[F],
    contextShift: ContextShift[F]): F[Unit] =
    crStream
      .map(_.toNJProducerRecord.noMeta)
      .through(sk.uploader(otherTopic, params.uploadRate))
      .map(_ => print("."))
      .compile
      .drain

  def stats: Statistics[F] =
    new Statistics(TypedDataset.create(rdd.map(CRMetaInfo(_))).dataset, cfg)
}
