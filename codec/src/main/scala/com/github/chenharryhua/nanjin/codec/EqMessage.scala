package com.github.chenharryhua.nanjin.codec

import java.util.Optional

import akka.kafka.ConsumerMessage.{
  CommittableMessage  => AkkaConsumerMessage,
  CommittableOffset   => AkkaCommittableOffset,
  GroupTopicPartition => AkkaGroupTopicPartition,
  PartitionOffset     => AkkaPartitionOffset
}
import akka.kafka.ProducerMessage.{Message => AkkaProducerMessage}
import cats.Eq
import cats.implicits._
import fs2.kafka.{
  CommittableConsumerRecord => Fs2CommittableConsumerRecord,
  CommittableOffset         => Fs2CommittableOffset,
  ConsumerRecord            => Fs2ConsumerRecord,
  ProducerRecord            => Fs2ProducerRecord,
  ProducerRecords           => Fs2ProducerRecords
}
import org.apache.kafka.clients.consumer.{ConsumerRecord, OffsetAndMetadata}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.{Header, Headers}

import scala.compat.java8.OptionConverters._

trait EqMessage {
  implicit val eqArrayByte: Eq[Array[Byte]] =
    (x: Array[Byte], y: Array[Byte]) => x.sameElements(y)

  implicit val eqHeader: Eq[Header] = (x: Header, y: Header) =>
    (x.key() === y.key()) && (x.value() === y.value())

  implicit val eqHeaders: Eq[Headers] = (x: Headers, y: Headers) => {
    val xa = x.toArray
    val ya = y.toArray
    (xa.size === ya.size) && xa.zip(ya).forall { case (x, y) => x === y }
  }

  implicit val eqOptionalInteger: Eq[Optional[java.lang.Integer]] =
    (x: Optional[Integer], y: Optional[Integer]) =>
      x.asScala.flatMap(Option(_).map(_.toInt)) === y.asScala.flatMap(Option(_).map(_.toInt))

  implicit val eqTopicPartition: Eq[TopicPartition] =
    (x: TopicPartition, y: TopicPartition) => x.equals(y)

  implicit val eqOffsetAndMetadata: Eq[OffsetAndMetadata] =
    (x: OffsetAndMetadata, y: OffsetAndMetadata) => x.equals(y)

  implicit final def eqConsumerRecord[K: Eq, V: Eq]: Eq[ConsumerRecord[K, V]] =
    (x: ConsumerRecord[K, V], y: ConsumerRecord[K, V]) =>
      (x.topic() === y.topic) &&
        (x.partition() === y.partition()) &&
        (x.offset() === y.offset()) &&
        (x.timestamp() === y.timestamp()) &&
        (x.timestampType().id === y.timestampType().id) &&
        (x.serializedKeySize() === y.serializedKeySize()) &&
        (x.serializedValueSize() === y.serializedValueSize()) &&
        (x.key() === y.key()) &&
        (x.value() === y.value()) &&
        (x.headers() === y.headers()) &&
        (x.leaderEpoch() === y.leaderEpoch())

  implicit final def eqProducerRecord[K: Eq, V: Eq]: Eq[ProducerRecord[K, V]] =
    (x: ProducerRecord[K, V], y: ProducerRecord[K, V]) =>
      (x.topic() === y.topic()) &&
        x.partition().equals(y.partition()) &&
        x.timestamp().equals(y.timestamp()) &&
        (x.key() === y.key()) &&
        (x.value() === y.value()) &&
        (x.headers() === y.headers())

  implicit val eqGroupTopicPartitionAkka: Eq[AkkaGroupTopicPartition] =
    cats.derived.semi.eq[AkkaGroupTopicPartition]

  implicit val eqPartitionOffsetAkka: Eq[AkkaPartitionOffset] =
    cats.derived.semi.eq[AkkaPartitionOffset]

  implicit val eqCommittableOffsetAkka: Eq[AkkaCommittableOffset] =
    (x: AkkaCommittableOffset, y: AkkaCommittableOffset) => x.partitionOffset === y.partitionOffset

  implicit def eqCommittableMessageAkka[K: Eq, V: Eq]: Eq[AkkaConsumerMessage[K, V]] =
    cats.derived.semi.eq[AkkaConsumerMessage[K, V]]

  implicit def eqProducerMessageAkka[K: Eq, V: Eq, P: Eq]: Eq[AkkaProducerMessage[K, V, P]] =
    cats.derived.semi.eq[AkkaProducerMessage[K, V, P]]

  implicit def eqCommittableOffsetFs2[F[_]]: Eq[Fs2CommittableOffset[F]] =
    (x: Fs2CommittableOffset[F], y: Fs2CommittableOffset[F]) =>
      (x.topicPartition === y.topicPartition) &&
        (x.consumerGroupId === y.consumerGroupId) &&
        (x.offsetAndMetadata === y.offsetAndMetadata) &&
        (x.offsets === y.offsets)

  implicit final def eqConsumerRecordFs2[K: Eq, V: Eq]: Eq[Fs2ConsumerRecord[K, V]] =
    (x: Fs2ConsumerRecord[K, V], y: Fs2ConsumerRecord[K, V]) =>
      isoFs2ComsumerRecord.get(x) === isoFs2ComsumerRecord.get(y)

  implicit final def eqProducerRecordFs2[K: Eq, V: Eq]: Eq[Fs2ProducerRecord[K, V]] =
    (x: Fs2ProducerRecord[K, V], y: Fs2ProducerRecord[K, V]) =>
      isoFs2ProducerRecord.get(x) === isoFs2ProducerRecord.get(y)

  implicit final def eqProducerRecordsFs2[K: Eq, V: Eq, P: Eq]: Eq[Fs2ProducerRecords[K, V, P]] =
    (x: Fs2ProducerRecords[K, V, P], y: Fs2ProducerRecords[K, V, P]) =>
      (x.records === y.records) &&
        (x.passthrough === y.passthrough)

  implicit final def eqCommittableConsumerRecordFs2[F[_], K: Eq, V: Eq]
    : Eq[Fs2CommittableConsumerRecord[F, K, V]] =
    (x: Fs2CommittableConsumerRecord[F, K, V], y: Fs2CommittableConsumerRecord[F, K, V]) =>
      (x.record === y.record) && (x.offset === y.offset)
}
