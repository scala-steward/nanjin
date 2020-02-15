package com.github.chenharryhua.nanjin.spark.streaming

import com.github.chenharryhua.nanjin.datetime.NJTimestamp
import com.github.chenharryhua.nanjin.kafka.common.NJConsumerRecord
import frameless.{TypedDataset, TypedEncoder}
import org.apache.spark.sql.Dataset

final class KafkaCRStream[F[_], K: TypedEncoder, V: TypedEncoder](
  ds: Dataset[NJConsumerRecord[K, V]],
  cfg: StreamConfig)
    extends SparkStreamUpdateParams[KafkaCRStream[F, K, V]] {

  override def withParamUpdate(f: StreamConfig => StreamConfig): KafkaCRStream[F, K, V] =
    new KafkaCRStream[F, K, V](ds, f(cfg))

  @transient lazy val typedDataset: TypedDataset[NJConsumerRecord[K, V]] = TypedDataset.create(ds)

  override val params: StreamParams = StreamConfigF.evalConfig(cfg)

  def someValues: KafkaCRStream[F, K, V] =
    new KafkaCRStream[F, K, V](typedDataset.filter(typedDataset('value).isNotNone).dataset, cfg)

  def datePartitionFileSink(path: String): DatePartitionFileSink[F, K, V] =
    new DatePartitionFileSink[F, K, V](typedDataset.deserialized.map { m =>
      val time = NJTimestamp(m.timestamp / 1000)
      val tz   = params.timeRange.zoneId
      DatePartitionedCR(
        time.yearStr(tz),
        time.monthStr(tz),
        time.dayStr(tz),
        m.partition,
        m.offset,
        m.key,
        m.value)
    }.dataset.writeStream, cfg, path)

  def sparkStream: SparkStream[F, NJConsumerRecord[K, V]] =
    new SparkStream[F, NJConsumerRecord[K, V]](ds, cfg)

  def toProducerRecords: KafkaPRStream[F, K, V] =
    new KafkaPRStream[F, K, V](typedDataset.deserialized.map(_.toNJProducerRecord).dataset, cfg)
}
