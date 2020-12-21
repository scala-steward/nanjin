package com.github.chenharryhua.nanjin.spark.kafka

import cats.effect.Sync
import cats.syntax.bifunctor._
import com.github.chenharryhua.nanjin.datetime.NJTimestamp
import com.github.chenharryhua.nanjin.kafka.KafkaTopic
import com.github.chenharryhua.nanjin.spark.AvroTypedEncoder
import com.github.chenharryhua.nanjin.spark.persist.DatasetAvroFileHoarder
import frameless.cats.implicits.framelessCatsSparkDelayForSync
import frameless.{TypedDataset, TypedEncoder, TypedExpressionEncoder}
import org.apache.spark.sql.Dataset

final class CrDS[F[_], K, V] private[kafka] (
  val topic: KafkaTopic[F, K, V],
  val dataset: Dataset[OptionalKV[K, V]],
  val ate: AvroTypedEncoder[OptionalKV[K, V]],
  val cfg: SKConfig)
    extends Serializable {

  val params: SKParams = cfg.evalConfig

  def repartition(num: Int): CrDS[F, K, V] =
    new CrDS[F, K, V](topic, dataset.repartition(num), ate, cfg)

  def partitionOf(num: Int): CrDS[F, K, V] = {
    import org.apache.spark.sql.functions.col
    new CrDS[F, K, V](topic, dataset.filter(col("partition") === num), ate, cfg)
  }

  def persist: CrDS[F, K, V]   = new CrDS[F, K, V](topic, dataset.persist(), ate, cfg)
  def unpersist: CrDS[F, K, V] = new CrDS[F, K, V](topic, dataset.unpersist(), ate, cfg)

  def bimap[K2, V2](k: K => K2, v: V => V2)(other: KafkaTopic[F, K2, V2])(implicit
    k2: TypedEncoder[K2],
    v2: TypedEncoder[V2]): CrDS[F, K2, V2] = {
    val ate: AvroTypedEncoder[OptionalKV[K2, V2]] = OptionalKV.ate(other.topicDef)
    new CrDS[F, K2, V2](other, dataset.map(_.bimap(k, v))(ate.sparkEncoder), ate, cfg).normalize
  }

  def map[K2, V2](f: OptionalKV[K, V] => OptionalKV[K2, V2])(other: KafkaTopic[F, K2, V2])(implicit
    k2: TypedEncoder[K2],
    v2: TypedEncoder[V2]): CrDS[F, K2, V2] = {
    val ate: AvroTypedEncoder[OptionalKV[K2, V2]] = OptionalKV.ate(other.topicDef)
    new CrDS[F, K2, V2](other, dataset.map(f)(ate.sparkEncoder), ate, cfg).normalize

  }

  def flatMap[K2, V2](f: OptionalKV[K, V] => TraversableOnce[OptionalKV[K2, V2]])(
    other: KafkaTopic[F, K2, V2])(implicit
    k2: TypedEncoder[K2],
    v2: TypedEncoder[V2]): CrDS[F, K2, V2] = {
    val ate: AvroTypedEncoder[OptionalKV[K2, V2]] = OptionalKV.ate(other.topicDef)
    new CrDS[F, K2, V2](other, dataset.flatMap(f)(ate.sparkEncoder), ate, cfg).normalize

  }

  def normalize: CrDS[F, K, V] = new CrDS[F, K, V](topic, ate.normalize(dataset).dataset, ate, cfg)

  def filter(f: OptionalKV[K, V] => Boolean): CrDS[F, K, V] =
    new CrDS[F, K, V](topic, dataset.filter(f), ate, cfg)

  def union(other: Dataset[OptionalKV[K, V]]): CrDS[F, K, V] =
    new CrDS[F, K, V](topic, dataset.union(other), ate, cfg)

  def union(other: CrDS[F, K, V]): CrDS[F, K, V] =
    union(other.dataset)

  def distinct: CrDS[F, K, V] =
    new CrDS[F, K, V](topic, dataset.distinct, ate, cfg)

  def typedDataset: TypedDataset[OptionalKV[K, V]] = TypedDataset.create(dataset)(ate.typedEncoder)

  def ascending: CrDS[F, K, V] = {
    val tds = typedDataset
    new CrDS[F, K, V](
      topic,
      tds.orderBy(tds('timestamp).asc, tds('offset).asc, tds('partition).asc).dataset,
      ate,
      cfg)
  }

  def descending: CrDS[F, K, V] = {
    val tds = typedDataset
    new CrDS[F, K, V](
      topic,
      tds.orderBy(tds('timestamp).desc, tds('offset).desc, tds('partition).desc).dataset,
      ate,
      cfg)
  }

  def count(implicit F: Sync[F]): F[Long] = F.delay(dataset.count())

  def stats: Statistics[F] = {
    val enc = TypedExpressionEncoder[CRMetaInfo]
    new Statistics[F](dataset.map(CRMetaInfo(_))(enc), cfg)
  }

  def first(implicit F: Sync[F]): F[Option[OptionalKV[K, V]]] =
    ascending.typedDataset.headOption()

  def last(implicit F: Sync[F]): F[Option[OptionalKV[K, V]]] =
    descending.typedDataset.headOption()

  def crRdd: CrRdd[F, K, V] = new CrRdd[F, K, V](topic, dataset.rdd, cfg)(dataset.sparkSession)
  def prRdd: PrRdd[F, K, V] = new PrRdd[F, K, V](topic, dataset.rdd.map(_.toNJProducerRecord), cfg)

  def save: DatasetAvroFileHoarder[F, OptionalKV[K, V]] =
    new DatasetAvroFileHoarder[F, OptionalKV[K, V]](dataset, ate.avroCodec.avroEncoder)

}
