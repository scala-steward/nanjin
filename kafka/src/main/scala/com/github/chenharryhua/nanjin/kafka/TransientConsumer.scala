package com.github.chenharryhua.nanjin.kafka

import cats.data.Kleisli
import cats.effect.kernel.{Resource, Sync}
import cats.mtl.Ask
import cats.syntax.all.*
import cats.{Id, Monad}
import com.github.chenharryhua.nanjin.common.kafka.TopicName
import com.github.chenharryhua.nanjin.datetime.{NJDateTimeRange, NJTimestamp}
import fs2.kafka.consumer.MkConsumer
import fs2.kafka.{ConsumerSettings, KafkaByteConsumer}
import org.apache.kafka.clients.consumer.{ConsumerRecord, OffsetAndMetadata}
import org.apache.kafka.common.{Metric, MetricName, TopicPartition}

import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.ScalaDurationOps

sealed trait KafkaPrimitiveConsumerApi[F[_]] {
  def partitionsFor: F[ListOfTopicPartitions]
  def beginningOffsets: F[KafkaTopicPartition[Option[KafkaOffset]]]
  def endOffsets: F[KafkaTopicPartition[Option[KafkaOffset]]]
  def offsetsForTimes(ts: NJTimestamp): F[KafkaTopicPartition[Option[KafkaOffset]]]

  def retrieveRecord(
    partition: KafkaPartition,
    offset: KafkaOffset): F[Option[ConsumerRecord[Array[Byte], Array[Byte]]]]

  def commitSync(offsets: Map[TopicPartition, OffsetAndMetadata]): F[Unit]

  def metrics: F[Map[MetricName, Metric]]
}

private object KafkaPrimitiveConsumerApi {

  def apply[F[_]: Monad](topicName: TopicName)(implicit
    F: Ask[F, KafkaByteConsumer]): KafkaPrimitiveConsumerApi[F] =
    new KafkaPrimitiveConsumerApiImpl[F](topicName)

  final private[this] class KafkaPrimitiveConsumerApiImpl[F[_]: Monad](topicName: TopicName)(implicit
    kbc: Ask[F, KafkaByteConsumer]
  ) extends KafkaPrimitiveConsumerApi[F] {

    override val partitionsFor: F[ListOfTopicPartitions] =
      kbc.ask.map { c =>
        val ret: List[TopicPartition] = c
          .partitionsFor(topicName.value)
          .asScala
          .toList
          .mapFilter(Option(_))
          .map(info => new TopicPartition(topicName.value, info.partition))
        ListOfTopicPartitions(ret)
      }

    @SuppressWarnings(Array("UnnecessaryConversion"))
    override val beginningOffsets: F[KafkaTopicPartition[Option[KafkaOffset]]] =
      for {
        tps <- partitionsFor
        ret <- kbc.ask.map {
          _.beginningOffsets(tps.asJava).asScala.toMap.view.mapValues(v =>
            Option(v).map(x => KafkaOffset(x.toLong)))
        }
      } yield KafkaTopicPartition(ret.toMap)

    @SuppressWarnings(Array("UnnecessaryConversion"))
    override val endOffsets: F[KafkaTopicPartition[Option[KafkaOffset]]] =
      for {
        tps <- partitionsFor
        ret <- kbc.ask.map {
          _.endOffsets(tps.asJava).asScala.toMap.view.mapValues(v =>
            Option(v).map(x => KafkaOffset(x.toLong)))
        }
      } yield KafkaTopicPartition(ret.toMap)

    override def offsetsForTimes(ts: NJTimestamp): F[KafkaTopicPartition[Option[KafkaOffset]]] =
      for {
        tps <- partitionsFor
        ret <- kbc.ask.map {
          _.offsetsForTimes(tps.javaTimed(ts)).asScala.toMap.view.mapValues(Option(_).map(x =>
            KafkaOffset(x.offset())))
        }
      } yield KafkaTopicPartition(ret.toMap)

    override def retrieveRecord(
      partition: KafkaPartition,
      offset: KafkaOffset): F[Option[ConsumerRecord[Array[Byte], Array[Byte]]]] =
      kbc.ask.map { consumer =>
        val tp = new TopicPartition(topicName.value, partition.value)
        consumer.assign(List(tp).asJava)
        consumer.seek(tp, offset.value)
        consumer.poll(Duration.ofSeconds(15)).records(tp).asScala.toList.headOption
      }

    override def commitSync(offsets: Map[TopicPartition, OffsetAndMetadata]): F[Unit] =
      kbc.ask.map(_.commitSync(offsets.asJava))

    override val metrics: F[Map[MetricName, Metric]] =
      kbc.ask.map(_.metrics().asScala.toMap)
  }
}

sealed trait TransientConsumer[F[_]] extends KafkaPrimitiveConsumerApi[F] {
  def offsetRangeFor(dtr: NJDateTimeRange): F[KafkaTopicPartition[Option[KafkaOffsetRange]]]
  def offsetRangeFor(start: NJTimestamp, end: NJTimestamp): F[KafkaTopicPartition[Option[KafkaOffsetRange]]]
  def offsetRangeForAll: F[KafkaTopicPartition[Option[KafkaOffsetRange]]]

  def retrieveLastRecords: F[List[ConsumerRecord[Array[Byte], Array[Byte]]]]
  def retrieveFirstRecords: F[List[ConsumerRecord[Array[Byte], Array[Byte]]]]
  def retrieveRecordsForTimes(ts: NJTimestamp): F[List[ConsumerRecord[Array[Byte], Array[Byte]]]]
  def numOfRecordsSince(ts: NJTimestamp): F[KafkaTopicPartition[Option[KafkaOffsetRange]]]

  def resetOffsetsToBegin: F[Unit]
  def resetOffsetsToEnd: F[Unit]
  def resetOffsetsForTimes(ts: NJTimestamp): F[Unit]
}

private object TransientConsumer {
  type PureConsumerSettings = ConsumerSettings[Id, Nothing, Nothing]

  def apply[F[_]: Sync](topicName: TopicName, cs: PureConsumerSettings): TransientConsumer[F] =
    new TransientConsumerImpl(topicName, cs)

  final private class TransientConsumerImpl[F[_]: Sync](topicName: TopicName, cs: PureConsumerSettings)
      extends TransientConsumer[F] {

    private[this] val consumer: Resource[F, KafkaByteConsumer] =
      Resource.make(MkConsumer.mkConsumerForSync[F].apply(cs))(c =>
        Sync[F].blocking(c.close(cs.closeTimeout.toJava)))

    private[this] val kpc: KafkaPrimitiveConsumerApi[Kleisli[F, KafkaByteConsumer, *]] =
      KafkaPrimitiveConsumerApi[Kleisli[F, KafkaByteConsumer, *]](topicName)

    private[this] def execute[A](r: Kleisli[F, KafkaByteConsumer, A]): F[A] =
      consumer.use(r.run)

    override def offsetRangeFor(dtr: NJDateTimeRange): F[KafkaTopicPartition[Option[KafkaOffsetRange]]] =
      execute {
        for {
          from <- dtr.startTimestamp.fold(kpc.beginningOffsets)(kpc.offsetsForTimes)
          end <- kpc.endOffsets
          to <- dtr.endTimestamp.traverse(kpc.offsetsForTimes)
        } yield offsetRange(from, end, to)
      }

    override def offsetRangeFor(
      start: NJTimestamp,
      end: NJTimestamp): F[KafkaTopicPartition[Option[KafkaOffsetRange]]] =
      execute {
        for {
          from <- kpc.offsetsForTimes(start)
          to <- kpc.offsetsForTimes(end)
        } yield offsetRange(from, to)
      }

    override val retrieveLastRecords: F[List[ConsumerRecord[Array[Byte], Array[Byte]]]] =
      execute {
        for {
          end <- kpc.endOffsets
          rec <- end.value.toList.traverse { case (tp, of) =>
            of.filter(_.value > 0)
              .flatTraverse(x => kpc.retrieveRecord(KafkaPartition(tp.partition), x.asLast))
          }
        } yield rec.flatten.sortBy(_.partition())
      }

    override val retrieveFirstRecords: F[List[ConsumerRecord[Array[Byte], Array[Byte]]]] =
      execute {
        for {
          beg <- kpc.beginningOffsets
          rec <- beg.value.toList.traverse { case (tp, of) =>
            of.flatTraverse(x => kpc.retrieveRecord(KafkaPartition(tp.partition), x))
          }
        } yield rec.flatten.sortBy(_.partition())
      }

    override def retrieveRecordsForTimes(ts: NJTimestamp): F[List[ConsumerRecord[Array[Byte], Array[Byte]]]] =
      execute {
        for {
          oft <- kpc.offsetsForTimes(ts)
          rec <- oft.value.toList.traverse { case (tp, of) =>
            of.flatTraverse(x => kpc.retrieveRecord(KafkaPartition(tp.partition), x))
          }
        } yield rec.flatten
      }

    override val offsetRangeForAll: F[KafkaTopicPartition[Option[KafkaOffsetRange]]] =
      execute {
        for {
          beg <- kpc.beginningOffsets
          end <- kpc.endOffsets
        } yield offsetRange(beg, end)
      }

    override def numOfRecordsSince(ts: NJTimestamp): F[KafkaTopicPartition[Option[KafkaOffsetRange]]] =
      execute {
        for {
          oft <- kpc.offsetsForTimes(ts)
          end <- kpc.endOffsets
        } yield offsetRange(oft, end)
      }

    override val partitionsFor: F[ListOfTopicPartitions] =
      execute(kpc.partitionsFor)

    override val beginningOffsets: F[KafkaTopicPartition[Option[KafkaOffset]]] =
      execute(kpc.beginningOffsets)

    override val endOffsets: F[KafkaTopicPartition[Option[KafkaOffset]]] =
      execute(kpc.endOffsets)

    override def offsetsForTimes(ts: NJTimestamp): F[KafkaTopicPartition[Option[KafkaOffset]]] =
      execute(kpc.offsetsForTimes(ts))

    override def retrieveRecord(
      partition: KafkaPartition,
      offset: KafkaOffset): F[Option[ConsumerRecord[Array[Byte], Array[Byte]]]] =
      execute(kpc.retrieveRecord(partition, offset))

    override def commitSync(offsets: Map[TopicPartition, OffsetAndMetadata]): F[Unit] =
      execute(kpc.commitSync(offsets))

    private def offsetsOf(
      offsets: KafkaTopicPartition[Option[KafkaOffset]]): Map[TopicPartition, OffsetAndMetadata] =
      offsets.flatten.value.view.mapValues(x => new OffsetAndMetadata(x.value)).toMap

    override val resetOffsetsToBegin: F[Unit] =
      execute(kpc.beginningOffsets.flatMap(x => kpc.commitSync(offsetsOf(x))))

    override val resetOffsetsToEnd: F[Unit] =
      execute(kpc.endOffsets.flatMap(x => kpc.commitSync(offsetsOf(x))))

    override def resetOffsetsForTimes(ts: NJTimestamp): F[Unit] =
      execute(kpc.offsetsForTimes(ts).flatMap(x => kpc.commitSync(offsetsOf(x))))

    override val metrics: F[Map[MetricName, Metric]] =
      execute(kpc.metrics)
  }
}
