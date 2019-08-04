package com.github.chenharryhua.nanjin.kafka

import akka.kafka.ConsumerMessage.{CommittableMessage => AkkaCommittableMessage}
import cats.Show
import cats.implicits._
import fs2.kafka.{CommittableMessage => Fs2CommittableMessage}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord

sealed trait LowerPriorityShow {

  implicit protected def showConsumerRecords2[K, V]: Show[ConsumerRecord[K, V]] =
    (t: ConsumerRecord[K, V]) => {
      val (utc, local) = utils.kafkaTimeStamp(t.timestamp())
      s"""|consumer record:
          |topic:        ${t.topic()}
          |partition:    ${t.partition()}
          |offset:       ${t.offset()}
          |headers:      ${t.headers()}
          |timestamp:    ${t.timestamp()}
          |utc:          $utc
          |local-time:   $local
          |ts-type:      ${t.timestampType()}
          |key:          ${Option(t.key).getOrElse("null")}
          |value:        ${Option(t.value).getOrElse("null")}
          |key-size:     ${t.serializedKeySize()}
          |value-size:   ${t.serializedValueSize()}
          |leader epoch: ${t.leaderEpoch}""".stripMargin
    }

  implicit protected def showFs2CommittableMessage2[F[_], K, V]
    : Show[Fs2CommittableMessage[F, K, V]] =
    (t: Fs2CommittableMessage[F, K, V]) => {
      s"""
         |fs2 committable message:
         |${t.record.show}
         |${t.committableOffset}""".stripMargin
    }

  implicit protected def showAkkaCommittableMessage2[K, V]: Show[AkkaCommittableMessage[K, V]] =
    (t: AkkaCommittableMessage[K, V]) => {
      s"""
         |akka committable message:
         |${t.record.show}
         |${t.committableOffset}""".stripMargin
    }
}

trait ShowKafkaMessage extends LowerPriorityShow {

  implicit protected def showConsumerRecords[K, V: Show]: Show[ConsumerRecord[K, V]] =
    (t: ConsumerRecord[K, V]) => {
      val (utc, local) = utils.kafkaTimeStamp(t.timestamp())
      s"""|consumer record:
          |topic:        ${t.topic()}
          |partition:    ${t.partition()}
          |offset:       ${t.offset()}
          |headers:      ${t.headers()}
          |timestamp:    ${t.timestamp()}
          |utc:          $utc
          |local-time:   $local
          |ts-type:      ${t.timestampType()}
          |key:          ${Option(t.key).getOrElse("null")}
          |value:        ${Option(t.value).map(_.show).getOrElse("null")}
          |key-size:     ${t.serializedKeySize()}
          |value-size:   ${t.serializedValueSize()}
          |leader epoch: ${t.leaderEpoch}""".stripMargin
    }

  implicit protected def showProducerRecord[K, V: Show]: Show[ProducerRecord[K, V]] =
    (t: ProducerRecord[K, V]) => {
      val (utc, local) = utils.kafkaTimeStamp(t.timestamp())
      s"""|producer record:
          |topic:      ${t.topic}
          |partition:  ${t.partition}
          |headers:    ${t.headers}
          |timestamp:  ${t.timestamp()}
          |utc:        $utc
          |local-time: $local
          |key:        ${Option(t.key).getOrElse("null")}
          |value:      ${Option(t.value).map(_.show).getOrElse("null")}""".stripMargin
    }

  implicit protected def showFs2CommittableMessage[F[_], K, V: Show]
    : Show[Fs2CommittableMessage[F, K, V]] =
    (t: Fs2CommittableMessage[F, K, V]) => {
      s"""
         |fs2 committable message:
         |${t.record.show}
         |${t.committableOffset}""".stripMargin
    }

  implicit protected def showAkkaCommittableMessage[K, V: Show]
    : Show[AkkaCommittableMessage[K, V]] =
    (t: AkkaCommittableMessage[K, V]) => {
      s"""
         |akka committable message:
         |${t.record.show}
         |${t.committableOffset}""".stripMargin
    }

  implicit protected val showArrayByte: Show[Array[Byte]] = _ => "Array[Byte]"
}
