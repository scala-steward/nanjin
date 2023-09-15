package com.github.chenharryhua.nanjin.kafka

import cats.Endo
import cats.data.NonEmptyList
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.common.UpdateConfig
import com.github.chenharryhua.nanjin.common.kafka.TopicName
import fs2.Stream
import fs2.kafka.{CommittableConsumerRecord, ConsumerSettings, KafkaConsumer}
import org.apache.avro.generic.GenericData

final class NJKafkaByteConsume[F[_]] private[kafka] (
  topicName: TopicName,
  consumerSettings: ConsumerSettings[F, Array[Byte], Array[Byte]],
  getSchema: F[AvroSchemaPair],
  srs: SchemaRegistrySettings
) extends UpdateConfig[ConsumerSettings[F, Array[Byte], Array[Byte]], NJKafkaByteConsume[F]] {

  override def updateConfig(f: Endo[ConsumerSettings[F, Array[Byte], Array[Byte]]]): NJKafkaByteConsume[F] =
    new NJKafkaByteConsume[F](topicName, f(consumerSettings), getSchema, srs)

  def resource(implicit F: Async[F]): Resource[F, KafkaConsumer[F, Array[Byte], Array[Byte]]] =
    KafkaConsumer.resource(consumerSettings)

  /** raw bytes from kafka, un-deserialized
    * @return
    *   bytes
    */
  def stream(implicit F: Async[F]): Stream[F, CommittableConsumerRecord[F, Array[Byte], Array[Byte]]] =
    KafkaConsumer
      .stream[F, Array[Byte], Array[Byte]](consumerSettings)
      .evalTap(_.subscribe(NonEmptyList.of(topicName.value)))
      .flatMap(_.stream)

  def assign(tps: KafkaTopicPartition[KafkaOffset])(implicit
    F: Async[F]): Stream[F, CommittableConsumerRecord[F, Array[Byte], Array[Byte]]] =
    if (tps.isEmpty)
      Stream.empty.covaryAll[F, CommittableConsumerRecord[F, Array[Byte], Array[Byte]]]
    else
      KafkaConsumer
        .stream[F, Array[Byte], Array[Byte]](consumerSettings)
        .evalTap { c =>
          c.assign(topicName.value) *> tps.value.toList.traverse { case (tp, offset) =>
            c.seek(tp, offset.value)
          }
        }
        .flatMap(_.stream)

  /** Retrieve Generic.Record from kafka
    *
    * @return
    *
    * an avro GenericData.Record instance of NJConsumerRecord
    *
    * key or value will be null if deserialization fails
    */
  def avro(implicit F: Async[F]): Stream[F, CommittableConsumerRecord[F, Unit, GenericData.Record]] =
    Stream.eval(getSchema).flatMap { skm =>
      val builder = new PullGenericRecord(srs, topicName, skm)
      stream.map { cr =>
        cr.bimap(_ => (), _ => builder.toGenericRecord(cr.record))
      }
    }

  // assignment

  def assignAvro(tps: KafkaTopicPartition[KafkaOffset])(implicit
    F: Async[F]): Stream[F, CommittableConsumerRecord[F, Unit, GenericData.Record]] =
    Stream.eval(getSchema).flatMap { skm =>
      val builder = new PullGenericRecord(srs, topicName, skm)
      assign(tps).map { cr =>
        cr.bimap(_ => (), _ => builder.toGenericRecord(cr.record))
      }
    }
}

final class NJKafkaConsume[F[_], K, V] private[kafka] (
  topicName: TopicName,
  consumerSettings: ConsumerSettings[F, K, V]
) extends UpdateConfig[ConsumerSettings[F, K, V], NJKafkaConsume[F, K, V]] {

  override def updateConfig(f: Endo[ConsumerSettings[F, K, V]]): NJKafkaConsume[F, K, V] =
    new NJKafkaConsume[F, K, V](topicName, f(consumerSettings))

  def resource(implicit F: Async[F]): Resource[F, KafkaConsumer[F, K, V]] =
    KafkaConsumer.resource(consumerSettings)

  def stream(implicit F: Async[F]): Stream[F, CommittableConsumerRecord[F, K, V]] =
    KafkaConsumer
      .stream[F, K, V](consumerSettings)
      .evalTap(_.subscribe(NonEmptyList.of(topicName.value)))
      .flatMap(_.stream)

  def assign(tps: KafkaTopicPartition[KafkaOffset])(implicit
    F: Async[F]): Stream[F, CommittableConsumerRecord[F, K, V]] =
    if (tps.isEmpty)
      Stream.empty.covaryAll[F, CommittableConsumerRecord[F, K, V]]
    else
      KafkaConsumer
        .stream[F, K, V](consumerSettings)
        .evalTap { c =>
          c.assign(topicName.value) *> tps.value.toList.traverse { case (tp, offset) =>
            c.seek(tp, offset.value)
          }
        }
        .flatMap(_.stream)
}
