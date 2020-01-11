package com.github.chenharryhua.nanjin.kafka.api

import java.time.Duration

import cats.Monad
import cats.data.Kleisli
import cats.effect.{Resource, Sync}
import cats.implicits._
import cats.mtl.ApplicativeAsk
import com.github.chenharryhua.nanjin.datetime.{NJDateTimeRange, NJTimestamp}
import com.github.chenharryhua.nanjin.kafka.{
  KafkaOffset,
  KafkaOffsetRange,
  KafkaPartition,
  KafkaTopicDescription,
  ListOfTopicPartitions,
  NJTopicPartition,
  TopicName
}
import fs2.kafka.KafkaByteConsumer
import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.collection.JavaConverters._

sealed trait KafkaPrimitiveConsumerApi[F[_]] {
  def partitionsFor: F[ListOfTopicPartitions]
  def beginningOffsets: F[NJTopicPartition[Option[KafkaOffset]]]
  def endOffsets: F[NJTopicPartition[Option[KafkaOffset]]]
  def offsetsForTimes(ts: NJTimestamp): F[NJTopicPartition[Option[KafkaOffset]]]

  def retrieveRecord(
    partition: KafkaPartition,
    offset: KafkaOffset): F[Option[ConsumerRecord[Array[Byte], Array[Byte]]]]

  def commitSync(offsets: Map[TopicPartition, OffsetAndMetadata]): F[Unit]
}

private[kafka] object KafkaPrimitiveConsumerApi {

  def apply[F[_]: Monad](topicName: TopicName)(
    implicit F: ApplicativeAsk[F, KafkaByteConsumer]): KafkaPrimitiveConsumerApi[F] =
    new KafkaPrimitiveConsumerApiImpl[F](topicName)

  final private[this] class KafkaPrimitiveConsumerApiImpl[F[_]: Monad](topicName: TopicName)(
    implicit kbc: ApplicativeAsk[F, KafkaByteConsumer]
  ) extends KafkaPrimitiveConsumerApi[F] {

    val partitionsFor: F[ListOfTopicPartitions] =
      kbc.ask.map { c =>
        val ret: List[TopicPartition] = c
          .partitionsFor(topicName.value)
          .asScala
          .toList
          .mapFilter(Option(_))
          .map(info => new TopicPartition(topicName.value, info.partition))
        ListOfTopicPartitions(ret)
      }

    val beginningOffsets: F[NJTopicPartition[Option[KafkaOffset]]] =
      for {
        tps <- partitionsFor
        ret <- kbc.ask.map {
          _.beginningOffsets(tps.asJava).asScala.toMap.mapValues(v =>
            Option(v).map(x => KafkaOffset(x.toLong)))
        }
      } yield NJTopicPartition(ret)

    val endOffsets: F[NJTopicPartition[Option[KafkaOffset]]] =
      for {
        tps <- partitionsFor
        ret <- kbc.ask.map {
          _.endOffsets(tps.asJava).asScala.toMap.mapValues(v =>
            Option(v).map(x => KafkaOffset(x.toLong)))
        }
      } yield NJTopicPartition(ret)

    override def offsetsForTimes(ts: NJTimestamp): F[NJTopicPartition[Option[KafkaOffset]]] =
      for {
        tps <- partitionsFor
        ret <- kbc.ask.map {
          _.offsetsForTimes(tps.javaTimed(ts)).asScala.toMap.mapValues(Option(_).map(x =>
            KafkaOffset(x.offset())))
        }
      } yield NJTopicPartition(ret)

    def retrieveRecord(
      partition: KafkaPartition,
      offset: KafkaOffset): F[Option[ConsumerRecord[Array[Byte], Array[Byte]]]] =
      kbc.ask.map { consumer =>
        val tp = new TopicPartition(topicName.value, partition.value)
        consumer.assign(List(tp).asJava)
        consumer.seek(tp, offset.value)
        consumer.poll(Duration.ofSeconds(15)).records(tp).asScala.toList.headOption
      }

    def commitSync(offsets: Map[TopicPartition, OffsetAndMetadata]): F[Unit] =
      kbc.ask.map(_.commitSync(offsets.asJava))
  }
}

sealed trait KafkaConsumerApi[F[_]] extends KafkaPrimitiveConsumerApi[F] {
  def offsetRangeFor(dtr: NJDateTimeRange): F[NJTopicPartition[KafkaOffsetRange]]
  def retrieveLastRecords: F[List[ConsumerRecord[Array[Byte], Array[Byte]]]]
  def retrieveFirstRecords: F[List[ConsumerRecord[Array[Byte], Array[Byte]]]]
  def retrieveRecordsForTimes(ts: NJTimestamp): F[List[ConsumerRecord[Array[Byte], Array[Byte]]]]
  def numOfRecords: F[NJTopicPartition[Option[KafkaOffsetRange]]]
  def numOfRecordsSince(ts: NJTimestamp): F[NJTopicPartition[Option[KafkaOffsetRange]]]

  def resetOffsetsToBegin: F[Unit]
  def resetOffsetsToEnd: F[Unit]
  def resetOffsetsForTimes(ts: NJTimestamp): F[Unit]
}

object KafkaConsumerApi {

  def apply[F[_]: Sync, K, V](
    topic: KafkaTopicDescription[K, V]): Resource[F, KafkaConsumerApi[F]] =
    Resource
      .make(
        Sync[F].delay(
          new KafkaConsumer[Array[Byte], Array[Byte]](
            topic.settings.consumerSettings.consumerProperties,
            new ByteArrayDeserializer,
            new ByteArrayDeserializer)))(a => Sync[F].delay(a.close()))
      .map(new KafkaConsumerApiImpl(topic.topicDef.topicName, _))

  final private[this] class KafkaConsumerApiImpl[F[_]: Sync](
    topicName: TopicName,
    consumerClient: KafkaByteConsumer)
      extends KafkaConsumerApi[F] {
    import cats.mtl.implicits._

    private[this] val kpc: KafkaPrimitiveConsumerApi[Kleisli[F, KafkaByteConsumer, *]] =
      KafkaPrimitiveConsumerApi[Kleisli[F, KafkaByteConsumer, *]](topicName)

    private[this] def execute[A](r: Kleisli[F, KafkaByteConsumer, A]): F[A] =
      r.run(consumerClient)

    override def offsetRangeFor(dtr: NJDateTimeRange): F[NJTopicPartition[KafkaOffsetRange]] =
      execute {
        for {
          beg <- kpc.beginningOffsets
          end <- kpc.endOffsets
          from <- dtr.start.traverse(kpc.offsetsForTimes)
          dest <- dtr.end.traverse(kpc.offsetsForTimes)
        } yield {
          val s = from.fold(beg)(_.combineWith(beg)(_.orElse(_)))
          val e = dest.fold(end)(_.combineWith(end)(_.orElse(_)))
          s.combineWith(e)(Tuple2(_, _).mapN((f, s) => KafkaOffsetRange(f, s)))
            .flatten[KafkaOffsetRange]
        }
      }

    override def retrieveLastRecords: F[List[ConsumerRecord[Array[Byte], Array[Byte]]]] =
      execute {
        for {
          end <- kpc.endOffsets
          rec <- end.value.toList.traverse {
            case (tp, of) =>
              of.filter(_.value > 0)
                .flatTraverse(x => kpc.retrieveRecord(KafkaPartition(tp.partition), x.asLast))
          }
        } yield rec.flatten.sortBy(_.partition())
      }

    override def retrieveFirstRecords: F[List[ConsumerRecord[Array[Byte], Array[Byte]]]] =
      execute {
        for {
          beg <- kpc.beginningOffsets
          rec <- beg.value.toList.traverse {
            case (tp, of) =>
              of.flatTraverse(x => kpc.retrieveRecord(KafkaPartition(tp.partition), x))
          }
        } yield rec.flatten.sortBy(_.partition())
      }

    override def retrieveRecordsForTimes(
      ts: NJTimestamp): F[List[ConsumerRecord[Array[Byte], Array[Byte]]]] =
      execute {
        for {
          oft <- kpc.offsetsForTimes(ts)
          rec <- oft.value.toList.traverse {
            case (tp, of) =>
              of.flatTraverse(x => kpc.retrieveRecord(KafkaPartition(tp.partition), x))
          }
        } yield rec.flatten
      }

    override def numOfRecords: F[NJTopicPartition[Option[KafkaOffsetRange]]] =
      execute {
        for {
          beg <- kpc.beginningOffsets
          end <- kpc.endOffsets
        } yield beg.combineWith(end)(Tuple2(_, _).mapN(KafkaOffsetRange))
      }

    override def numOfRecordsSince(ts: NJTimestamp): F[NJTopicPartition[Option[KafkaOffsetRange]]] =
      execute {
        for {
          oft <- kpc.offsetsForTimes(ts)
          end <- kpc.endOffsets
        } yield oft.combineWith(end)(Tuple2(_, _).mapN(KafkaOffsetRange))
      }

    override def partitionsFor: F[ListOfTopicPartitions] =
      execute(kpc.partitionsFor)

    override def beginningOffsets: F[NJTopicPartition[Option[KafkaOffset]]] =
      execute(kpc.beginningOffsets)

    override def endOffsets: F[NJTopicPartition[Option[KafkaOffset]]] =
      execute(kpc.endOffsets)

    override def offsetsForTimes(ts: NJTimestamp): F[NJTopicPartition[Option[KafkaOffset]]] =
      execute(kpc.offsetsForTimes(ts))

    override def retrieveRecord(
      partition: KafkaPartition,
      offset: KafkaOffset): F[Option[ConsumerRecord[Array[Byte], Array[Byte]]]] =
      execute(kpc.retrieveRecord(partition, offset))

    override def commitSync(offsets: Map[TopicPartition, OffsetAndMetadata]): F[Unit] =
      execute(kpc.commitSync(offsets))

    private def offsetsOf(
      offsets: NJTopicPartition[Option[KafkaOffset]]): Map[TopicPartition, OffsetAndMetadata] =
      offsets.flatten[KafkaOffset].value.mapValues(x => new OffsetAndMetadata(x.value))

    override def resetOffsetsToBegin: F[Unit] =
      execute(kpc.beginningOffsets.flatMap(x => kpc.commitSync(offsetsOf(x))))

    override def resetOffsetsToEnd: F[Unit] =
      execute(kpc.endOffsets.flatMap(x => kpc.commitSync(offsetsOf(x))))

    override def resetOffsetsForTimes(ts: NJTimestamp): F[Unit] =
      execute(kpc.offsetsForTimes(ts).flatMap(x => kpc.commitSync(offsetsOf(x))))
  }
}
