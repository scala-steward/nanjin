package com.github.chenharryhua.nanjin.spark.streaming

import cats.implicits._
import com.github.chenharryhua.nanjin.kafka.KafkaTopicKit
import com.github.chenharryhua.nanjin.kafka.common.NJProducerRecord
import frameless.{TypedDataset, TypedEncoder}
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.streaming.{OutputMode, Trigger}

final class SparkStreamStart[F[_], A: TypedEncoder](
  ds: Dataset[A],
  params: StreamConfigF.StreamConfig)
    extends Serializable {
  @transient lazy val typedDataset: TypedDataset[A] = TypedDataset.create(ds)

  private val p: StreamParams = StreamConfigF.evalParams(params)

  def withOutputMode(om: OutputMode): SparkStreamStart[F, A] =
    new SparkStreamStart[F, A](ds, StreamConfigF.withOutputMode(om, params))

  def withCheckpoint(cp: String): SparkStreamStart[F, A] =
    new SparkStreamStart[F, A](ds, StreamConfigF.withCheckpoint(cp, params))

  def withCheckpointAppend(cp: String): SparkStreamStart[F, A] =
    new SparkStreamStart[F, A](ds, StreamConfigF.withCheckpointAppend(cp, params))

  def withFailOnDataLoss(dl: Boolean): SparkStreamStart[F, A] =
    new SparkStreamStart[F, A](ds, StreamConfigF.withFailOnDataLoss(dl, params))

  def withTrigger(t: Trigger): SparkStreamStart[F, A] =
    new SparkStreamStart[F, A](ds, StreamConfigF.withTrigger(t, params))

  // transforms

  def filter(f: A => Boolean): SparkStreamStart[F, A] =
    new SparkStreamStart[F, A](ds.filter(f), params)

  def map[B: TypedEncoder](f: A => B): SparkStreamStart[F, B] =
    new SparkStreamStart[F, B](typedDataset.deserialized.map(f).dataset, params)

  def flatMap[B: TypedEncoder](f: A => TraversableOnce[B]): SparkStreamStart[F, B] =
    new SparkStreamStart[F, B](typedDataset.deserialized.flatMap(f).dataset, params)

  // sinks

  def consoleSink: NJConsoleSink[F, A] =
    new NJConsoleSink[F, A](ds.writeStream, params)

  def fileSink(path: String): NJFileSink[F, A] =
    new NJFileSink[F, A](ds.writeStream, params, path)

  def kafkaSink[K, V](kit: KafkaTopicKit[K, V])(
    implicit pr: A =:= NJProducerRecord[K, V]): NJKafkaSink[F] =
    new NJKafkaSink[F](
      typedDataset.deserialized
        .map(m =>
          pr(m).bimap(
            k => kit.codec.keySerde.serializer.serialize(kit.topicName.value, k),
            v => kit.codec.valSerde.serializer.serialize(kit.topicName.value, v)))
        .dataset
        .writeStream,
      params,
      kit.settings.producerSettings,
      kit.topicName
    )
}
