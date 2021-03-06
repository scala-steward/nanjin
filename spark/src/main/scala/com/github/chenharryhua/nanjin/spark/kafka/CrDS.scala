package com.github.chenharryhua.nanjin.spark.kafka

import cats.Eq
import cats.effect.Sync
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.datetime.{NJDateTimeRange, NJTimestamp}
import com.github.chenharryhua.nanjin.kafka.KafkaTopic
import com.github.chenharryhua.nanjin.spark.AvroTypedEncoder
import com.github.chenharryhua.nanjin.spark.persist.DatasetAvroFileHoarder
import frameless.{TypedDataset, TypedEncoder, TypedExpressionEncoder}
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.functions.col

final class CrDS[F[_], K, V] private[kafka] (
  val dataset: Dataset[NJConsumerRecord[K, V]],
  topic: KafkaTopic[F, K, V],
  cfg: SKConfig,
  tek: TypedEncoder[K],
  tev: TypedEncoder[V])
    extends Serializable {

  val ate: AvroTypedEncoder[NJConsumerRecord[K, V]] = NJConsumerRecord.ate(topic.topicDef)(tek, tev)

  val params: SKParams = cfg.evalConfig

  def typedDataset: TypedDataset[NJConsumerRecord[K, V]] = TypedDataset.create(dataset)(ate.typedEncoder)

  // transforms
  def transform(f: Dataset[NJConsumerRecord[K, V]] => Dataset[NJConsumerRecord[K, V]]): CrDS[F, K, V] =
    new CrDS[F, K, V](dataset.transform(f), topic, cfg, tek, tev)

  def partitionOf(num: Int): CrDS[F, K, V] = transform(_.filter(col("partition") === num))

  def offsetRange(start: Long, end: Long): CrDS[F, K, V] = transform(range.offset(start, end))
  def timeRange(dr: NJDateTimeRange): CrDS[F, K, V]      = transform(range.timestamp(dr))
  def timeRange: CrDS[F, K, V]                           = timeRange(params.timeRange)

  def ascendOffset: CrDS[F, K, V]     = transform(sort.ascend.offset)
  def descendOffset: CrDS[F, K, V]    = transform(sort.descend.offset)
  def ascendTimestamp: CrDS[F, K, V]  = transform(sort.ascend.timestamp)
  def descendTimestamp: CrDS[F, K, V] = transform(sort.descend.timestamp)

  def union(other: CrDS[F, K, V]): CrDS[F, K, V] = transform(_.union(other.dataset))
  def repartition(num: Int): CrDS[F, K, V]       = transform(_.repartition(num))

  def normalize: CrDS[F, K, V] = transform(ate.normalize(_).dataset)

  def replicate(num: Int): CrDS[F, K, V] =
    transform(ds => (1 until num).foldLeft(ds) { case (r, _) => r.union(ds) })

  // maps
  def bimap[K2, V2](k: K => K2, v: V => V2)(
    other: KafkaTopic[F, K2, V2])(implicit k2: TypedEncoder[K2], v2: TypedEncoder[V2]): CrDS[F, K2, V2] = {
    val ate: AvroTypedEncoder[NJConsumerRecord[K2, V2]] = NJConsumerRecord.ate(other.topicDef)
    new CrDS[F, K2, V2](dataset.map(_.bimap(k, v))(ate.sparkEncoder), other, cfg, k2, v2).normalize
  }

  def map[K2, V2](f: NJConsumerRecord[K, V] => NJConsumerRecord[K2, V2])(
    other: KafkaTopic[F, K2, V2])(implicit k2: TypedEncoder[K2], v2: TypedEncoder[V2]): CrDS[F, K2, V2] = {
    val ate: AvroTypedEncoder[NJConsumerRecord[K2, V2]] = NJConsumerRecord.ate(other.topicDef)
    new CrDS[F, K2, V2](dataset.map(f)(ate.sparkEncoder), other, cfg, k2, v2).normalize
  }

  def flatMap[K2, V2](f: NJConsumerRecord[K, V] => TraversableOnce[NJConsumerRecord[K2, V2]])(
    other: KafkaTopic[F, K2, V2])(implicit k2: TypedEncoder[K2], v2: TypedEncoder[V2]): CrDS[F, K2, V2] = {
    val ate: AvroTypedEncoder[NJConsumerRecord[K2, V2]] = NJConsumerRecord.ate(other.topicDef)
    new CrDS[F, K2, V2](dataset.flatMap(f)(ate.sparkEncoder), other, cfg, k2, v2).normalize
  }

  // transition
  def save: DatasetAvroFileHoarder[F, NJConsumerRecord[K, V]] =
    new DatasetAvroFileHoarder[F, NJConsumerRecord[K, V]](dataset, ate.avroCodec.avroEncoder)

  def crRdd: CrRdd[F, K, V] = new CrRdd[F, K, V](dataset.rdd, topic, cfg, dataset.sparkSession)

  def prRdd: PrRdd[F, K, V] = new PrRdd[F, K, V](dataset.rdd.map(_.toNJProducerRecord), topic, cfg)

  // statistics
  def stats: Statistics[F] =
    new Statistics[F](dataset.map(CRMetaInfo(_))(TypedExpressionEncoder[CRMetaInfo]), params.timeRange.zoneId)

  def count(implicit F: Sync[F]): F[Long] = F.delay(dataset.count())

  def cherrypick(partition: Int, offset: Long): Option[NJConsumerRecord[K, V]] =
    partitionOf(partition).offsetRange(offset, offset).dataset.collect().headOption

  def diff(
    other: TypedDataset[NJConsumerRecord[K, V]])(implicit eqK: Eq[K], eqV: Eq[V]): TypedDataset[DiffResult[K, V]] =
    inv.diffDataset(typedDataset, other)(eqK, tek, eqV, tev)

  def diff(other: CrDS[F, K, V])(implicit eqK: Eq[K], eqV: Eq[V]): TypedDataset[DiffResult[K, V]] =
    diff(other.typedDataset)

  def diffKV(other: TypedDataset[NJConsumerRecord[K, V]]): TypedDataset[KvDiffResult[K, V]] =
    inv.kvDiffDataset(typedDataset, other)(tek, tev)

  def diffKV(other: CrDS[F, K, V]): TypedDataset[KvDiffResult[K, V]] = diffKV(other.typedDataset)

  /** Notes: same key should be in same partition.
    */
  def misplacedKey: TypedDataset[MisplacedKey[K]] = {
    import frameless.functions.aggregate.countDistinct
    implicit val enc: TypedEncoder[K]             = tek
    val tds: TypedDataset[NJConsumerRecord[K, V]] = typedDataset
    val res: TypedDataset[MisplacedKey[K]] =
      tds.groupBy(tds('key)).agg(countDistinct(tds('partition))).as[MisplacedKey[K]]
    res.filter(res('count) > 1).orderBy(res('count).asc)
  }

  /** Notes: timestamp order should follow offset order: the larger the offset is the larger of timestamp should be, of
    * the same key
    */
  def misorderedKey: TypedDataset[MisorderedKey[K]] = {
    implicit val enc: TypedEncoder[K]             = tek
    val tds: TypedDataset[NJConsumerRecord[K, V]] = typedDataset
    tds.groupBy(tds('key)).deserialized.flatMapGroups { case (key, iter) =>
      key.traverse { key =>
        iter.toList.sortBy(_.offset).sliding(2).toList.flatMap {
          case List(c, n) =>
            if (n.timestamp >= c.timestamp) None
            else
              Some(
                MisorderedKey(
                  key,
                  c.partition,
                  c.offset,
                  c.timestamp,
                  c.timestamp - n.timestamp,
                  n.offset - c.offset,
                  n.partition,
                  n.offset,
                  n.timestamp))
          case _ => None // single item list
        }
      }.flatten
    }
  }
}

final case class MisorderedKey[K](
  key: K,
  partition: Int,
  offset: Long,
  ts: Long,
  msGap: Long,
  offsetDistance: Long,
  nextPartition: Int,
  nextOffset: Long,
  nextTS: Long)

final case class MisplacedKey[K](key: Option[K], count: Long)
