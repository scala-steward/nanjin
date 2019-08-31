package com.github.chenharryhua.nanjin.kafka

import cats.implicits._
import cats.{Applicative, Bitraverse, Eval}
import com.github.ghik.silencer.silent
import monocle.Iso
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType

import scala.compat.java8.OptionConverters._

trait BitraverseKafkaRecord extends Serializable {

  implicit final val bitraverseConsumerRecord: Bitraverse[ConsumerRecord[?, ?]] =
    new Bitraverse[ConsumerRecord] {
      override def bimap[K1, V1, K2, V2](
        cr: ConsumerRecord[K1, V1])(k: K1 => K2, v: V1 => V2): ConsumerRecord[K2, V2] =
        new ConsumerRecord[K2, V2](
          cr.topic,
          cr.partition,
          cr.offset,
          cr.timestamp,
          cr.timestampType,
          cr.checksum: @silent,
          cr.serializedKeySize,
          cr.serializedValueSize,
          k(cr.key),
          v(cr.value),
          cr.headers,
          cr.leaderEpoch)

      override def bitraverse[G[_], A, B, C, D](fab: ConsumerRecord[A, B])(
        f: A => G[C],
        g: B => G[D])(implicit G: Applicative[G]): G[ConsumerRecord[C, D]] =
        G.map2(f(fab.key), g(fab.value))((k, v) => bimap(fab)(_ => k, _ => v))

      override def bifoldLeft[A, B, C](fab: ConsumerRecord[A, B], c: C)(
        f: (C, A) => C,
        g: (C, B) => C): C = g(f(c, fab.key), fab.value)

      override def bifoldRight[A, B, C](fab: ConsumerRecord[A, B], c: Eval[C])(
        f: (A, Eval[C]) => Eval[C],
        g: (B, Eval[C]) => Eval[C]): Eval[C] = g(fab.value, f(fab.key, c))
    }

  implicit final val bitraverseProducerRecord: Bitraverse[ProducerRecord[?, ?]] =
    new Bitraverse[ProducerRecord] {
      override def bimap[K1, V1, K2, V2](
        pr: ProducerRecord[K1, V1])(k: K1 => K2, v: V1 => V2): ProducerRecord[K2, V2] =
        new ProducerRecord[K2, V2](
          pr.topic,
          pr.partition,
          pr.timestamp,
          k(pr.key),
          v(pr.value),
          pr.headers)

      override def bitraverse[G[_], A, B, C, D](fab: ProducerRecord[A, B])(
        f: A => G[C],
        g: B => G[D])(implicit G: Applicative[G]): G[ProducerRecord[C, D]] =
        G.map2(f(fab.key), g(fab.value))((k, v) => bimap(fab)(_ => k, _ => v))

      override def bifoldLeft[A, B, C](fab: ProducerRecord[A, B], c: C)(
        f: (C, A) => C,
        g: (C, B) => C): C = g(f(c, fab.key), fab.value)

      override def bifoldRight[A, B, C](fab: ProducerRecord[A, B], c: Eval[C])(
        f: (A, Eval[C]) => Eval[C],
        g: (B, Eval[C]) => Eval[C]): Eval[C] = g(fab.value, f(fab.key, c))
    }
}

trait BitraverseFs2Message extends BitraverseKafkaRecord {

  import fs2.kafka.{
    CommittableConsumerRecord,
    Headers,
    Id,
    ProducerRecords,
    Timestamp,
    ConsumerRecord => Fs2ConsumerRecord,
    ProducerRecord => Fs2ProducerRecord
  }

  final def isoFs2ProducerRecord[F[_], K, V]: Iso[Fs2ProducerRecord[K, V], ProducerRecord[K, V]] =
    Iso[Fs2ProducerRecord[K, V], ProducerRecord[K, V]](
      fpr =>
        new ProducerRecord[K, V](
          fpr.topic,
          fpr.partition.map(new java.lang.Integer(_)).orNull,
          fpr.timestamp.map(new java.lang.Long(_)).orNull,
          fpr.key,
          fpr.value,
          fpr.headers.asJava))(
      pr =>
        Fs2ProducerRecord(pr.topic, pr.key, pr.value)
          .withPartition(pr.partition)
          .withTimestamp(pr.timestamp)
          .withHeaders(
            pr.headers.toArray.foldLeft(Headers.empty)((t, i) => t.append(i.key, i.value))))

  final def isoFs2ComsumerRecord[K, V]: Iso[Fs2ConsumerRecord[K, V], ConsumerRecord[K, V]] =
    Iso[Fs2ConsumerRecord[K, V], ConsumerRecord[K, V]](
      fcr =>
        new ConsumerRecord[K, V](
          fcr.topic,
          fcr.partition,
          fcr.offset,
          fcr.timestamp.createTime
            .orElse(fcr.timestamp.logAppendTime)
            .getOrElse(ConsumerRecord.NO_TIMESTAMP),
          fcr.timestamp.createTime
            .map(_ => TimestampType.CREATE_TIME)
            .orElse(fcr.timestamp.logAppendTime.map(_ => TimestampType.LOG_APPEND_TIME))
            .getOrElse(TimestampType.NO_TIMESTAMP_TYPE),
          ConsumerRecord.NULL_CHECKSUM,
          fcr.serializedKeySize.getOrElse(ConsumerRecord.NULL_SIZE),
          fcr.serializedValueSize.getOrElse(ConsumerRecord.NULL_SIZE),
          fcr.key,
          fcr.value,
          new RecordHeaders(fcr.headers.asJava),
          fcr.leaderEpoch.map(new Integer(_)).asJava
        ))(cr => {
      val epoch: Option[Int] = cr.leaderEpoch().asScala.map(_.intValue())
      val fcr =
        Fs2ConsumerRecord[K, V](cr.topic(), cr.partition(), cr.offset(), cr.key(), cr.value())
          .withHeaders(
            cr.headers.toArray.foldLeft(Headers.empty)((t, i) => t.append(i.key, i.value)))
          .withSerializedKeySize(cr.serializedKeySize())
          .withSerializedValueSize(cr.serializedValueSize())
          .withTimestamp(cr.timestampType match {
            case TimestampType.CREATE_TIME       => Timestamp.createTime(cr.timestamp())
            case TimestampType.LOG_APPEND_TIME   => Timestamp.logAppendTime(cr.timestamp())
            case TimestampType.NO_TIMESTAMP_TYPE => Timestamp.none
          })
      epoch.fold[Fs2ConsumerRecord[K, V]](fcr)(e => fcr.withLeaderEpoch(e))
    })

  implicit final def bitraverseFs2CommittableMessage[F[_]]
    : Bitraverse[CommittableConsumerRecord[F, ?, ?]] =
    new Bitraverse[CommittableConsumerRecord[F, ?, ?]] {
      override def bitraverse[G[_]: Applicative, A, B, C, D](
        fab: CommittableConsumerRecord[F, A, B])(
        f: A => G[C],
        g: B => G[D]): G[CommittableConsumerRecord[F, C, D]] =
        isoFs2ComsumerRecord
          .get(fab.record)
          .bitraverse(f, g)
          .map(r => CommittableConsumerRecord(isoFs2ComsumerRecord.reverseGet(r), fab.offset))

      override def bifoldLeft[A, B, C](fab: CommittableConsumerRecord[F, A, B], c: C)(
        f: (C, A) => C,
        g: (C, B) => C): C = isoFs2ComsumerRecord.get(fab.record).bifoldLeft(c)(f, g)

      override def bifoldRight[A, B, C](fab: CommittableConsumerRecord[F, A, B], c: Eval[C])(
        f: (A, Eval[C]) => Eval[C],
        g: (B, Eval[C]) => Eval[C]): Eval[C] =
        isoFs2ComsumerRecord.get(fab.record).bifoldRight(c)(f, g)
    }

  implicit final val bitraverseFs2ProducerRecord: Bitraverse[Fs2ProducerRecord[?, ?]] =
    new Bitraverse[Fs2ProducerRecord] {
      override def bitraverse[G[_]: Applicative, A, B, C, D](
        fab: Fs2ProducerRecord[A, B])(f: A => G[C], g: B => G[D]): G[Fs2ProducerRecord[C, D]] =
        isoFs2ProducerRecord.get(fab).bitraverse(f, g).map(i => isoFs2ProducerRecord.reverseGet(i))

      override def bifoldLeft[A, B, C](fab: Fs2ProducerRecord[A, B], c: C)(
        f: (C, A) => C,
        g: (C, B) => C): C = isoFs2ProducerRecord.get(fab).bifoldLeft(c)(f, g)

      override def bifoldRight[A, B, C](fab: Fs2ProducerRecord[A, B], c: Eval[C])(
        f: (A, Eval[C]) => Eval[C],
        g: (B, Eval[C]) => Eval[C]): Eval[C] = isoFs2ProducerRecord.get(fab).bifoldRight(c)(f, g)
    }

  implicit final def bitraverseFs2ProducerMessage[P]: Bitraverse[ProducerRecords[?, ?, P]] =
    new Bitraverse[ProducerRecords[?, ?, P]] {
      override def bitraverse[G[_]: Applicative, A, B, C, D](
        fab: ProducerRecords[A, B, P])(f: A => G[C], g: B => G[D]): G[ProducerRecords[C, D, P]] =
        fab.records.traverse(_.bitraverse(f, g)).map(p => ProducerRecords(p, fab.passthrough))

      override def bifoldLeft[A, B, C](fab: ProducerRecords[A, B, P], c: C)(
        f: (C, A) => C,
        g: (C, B) => C): C = fab.records.foldLeft(c) { case (ec, pr) => pr.bifoldLeft(ec)(f, g) }

      override def bifoldRight[A, B, C](fab: ProducerRecords[A, B, P], c: Eval[C])(
        f: (A, Eval[C]) => Eval[C],
        g: (B, Eval[C]) => Eval[C]): Eval[C] =
        fab.records.foldRight(c) { case (pr, ec) => pr.bifoldRight(ec)(f, g) }
    }
}

trait BitraverseAkkaMessage extends BitraverseKafkaRecord {
  import akka.kafka.ConsumerMessage.CommittableMessage
  import akka.kafka.ProducerMessage.Message

  implicit final def bitraverseAkkaProducerMessage[P]: Bitraverse[Message[?, ?, P]] =
    new Bitraverse[Message[?, ?, P]] {

      override def bitraverse[G[_]: Applicative, A, B, C, D](
        fab: Message[A, B, P])(f: A => G[C], g: B => G[D]): G[Message[C, D, P]] =
        fab.record.bitraverse(f, g).map(r => fab.copy(record = r))

      override def bifoldLeft[A, B, C](fab: Message[A, B, P], c: C)(
        f: (C, A) => C,
        g: (C, B) => C): C = fab.record.bifoldLeft(c)(f, g)

      override def bifoldRight[A, B, C](fab: Message[A, B, P], c: Eval[C])(
        f: (A, Eval[C]) => Eval[C],
        g: (B, Eval[C]) => Eval[C]): Eval[C] = fab.record.bifoldRight(c)(f, g)
    }

  implicit final val bitraverseAkkaCommittableMessage: Bitraverse[CommittableMessage[?, ?]] =
    new Bitraverse[CommittableMessage] {
      override def bitraverse[G[_]: Applicative, A, B, C, D](
        fab: CommittableMessage[A, B])(f: A => G[C], g: B => G[D]): G[CommittableMessage[C, D]] =
        fab.record.bitraverse(f, g).map(r => fab.copy(record = r))

      override def bifoldLeft[A, B, C](fab: CommittableMessage[A, B], c: C)(
        f: (C, A) => C,
        g: (C, B) => C): C = fab.record.bifoldLeft(c)(f, g)

      override def bifoldRight[A, B, C](fab: CommittableMessage[A, B], c: Eval[C])(
        f: (A, Eval[C]) => Eval[C],
        g: (B, Eval[C]) => Eval[C]): Eval[C] = fab.record.bifoldRight(c)(f, g)
    }
}
