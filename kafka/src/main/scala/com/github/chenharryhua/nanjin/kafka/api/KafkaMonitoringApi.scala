package com.github.chenharryhua.nanjin.kafka.api

import java.nio.file.{Path, Paths}

import cats.Show
import cats.effect.{Blocker, Concurrent, ContextShift}
import cats.implicits._
import com.github.chenharryhua.nanjin.common.NJRootPath
import com.github.chenharryhua.nanjin.kafka.codec.iso
import com.github.chenharryhua.nanjin.kafka.{KafkaChannels, KafkaTopic, _}
import fs2.kafka.AutoOffsetReset
import fs2.{text, Stream}
import org.apache.kafka.clients.consumer.ConsumerRecord

import scala.util.Try

sealed trait KafkaMonitoringApi[F[_], K, V] {
  def watch: F[Unit]
  def watchFromEarliest: F[Unit]

  def filter(pred: ConsumerRecord[Try[K], Try[V]]             => Boolean): F[Unit]
  def filterFromEarliest(pred: ConsumerRecord[Try[K], Try[V]] => Boolean): F[Unit]

  def badRecordsFromEarliest: F[Unit]
  def badRecords: F[Unit]

  def summaries: F[Unit]

  def saveJson: F[Unit]
  def replay: F[Unit]
}

private[kafka] object KafkaMonitoringApi {

  def apply[F[_]: Concurrent: ContextShift, K: Show, V: Show](
    topic: KafkaTopic[F, K, V],
    rootPath: NJRootPath): KafkaMonitoringApi[F, K, V] =
    new KafkaTopicMonitoring[F, K, V](topic, rootPath)

  final private class KafkaTopicMonitoring[F[_]: ContextShift, K: Show, V: Show](
    topic: KafkaTopic[F, K, V],
    rootPath: NJRootPath)(implicit F: Concurrent[F])
      extends KafkaMonitoringApi[F, K, V] {
    private val fs2Channel: KafkaChannels.Fs2Channel[F, K, V] = topic.fs2Channel
    private val consumer: KafkaConsumerApi[F, K, V]           = topic.consumer

    private def watch(aor: AutoOffsetReset): F[Unit] =
      fs2Channel
        .updateConsumerSettings(_.withAutoOffsetReset(aor))
        .consume
        .map(m => topic.decoder(m).tryDecodeKeyValue)
        .map(_.show)
        .showLinesStdOut
        .compile
        .drain

    private def filterWatch(
      predict: ConsumerRecord[Try[K], Try[V]] => Boolean,
      aor: AutoOffsetReset): F[Unit] =
      fs2Channel
        .updateConsumerSettings(_.withAutoOffsetReset(aor))
        .consume
        .map(m => topic.decoder(m).tryDecodeKeyValue)
        .filter(m => predict(iso.isoFs2ComsumerRecord.get(m.record)))
        .map(_.show)
        .showLinesStdOut
        .compile
        .drain

    override def watch: F[Unit]             = watch(AutoOffsetReset.Latest)
    override def watchFromEarliest: F[Unit] = watch(AutoOffsetReset.Earliest)

    override def filter(pred: ConsumerRecord[Try[K], Try[V]] => Boolean): F[Unit] =
      filterWatch(pred, AutoOffsetReset.Latest)

    override def filterFromEarliest(pred: ConsumerRecord[Try[K], Try[V]] => Boolean): F[Unit] =
      filterWatch(pred, AutoOffsetReset.Earliest)

    override def badRecordsFromEarliest: F[Unit] =
      filterFromEarliest(cr => cr.key().isFailure || cr.value().isFailure)

    override def badRecords: F[Unit] =
      filter(cr => cr.key().isFailure || cr.value().isFailure)

    override def summaries: F[Unit] =
      for {
        num <- consumer.numOfRecords
        first <- consumer.retrieveFirstRecords.map(_.map(cr => topic.decoder(cr).tryDecodeKeyValue))
        last <- consumer.retrieveLastRecords.map(_.map(cr   => topic.decoder(cr).tryDecodeKeyValue))
      } yield println(s"""
                         |summaries:
                         |
                         |number of records: $num
                         |first records of each partitions: 
                         |${first.map(_.show).mkString("\n")}
                         |
                         |last records of each partitions:
                         |${last.map(_.show).mkString("\n")}
                         |""".stripMargin)

    private val path: Path = Paths.get(rootPath + s"/json/${topic.topicDef.topicName}.json")

    override def saveJson: F[Unit] =
      Stream
        .resource[F, Blocker](Blocker[F])
        .flatMap { blocker =>
          fs2Channel.consume
            .map(x => topic.toJson(x).noSpaces)
            .intersperse("\n")
            .through(text.utf8Encode)
            .through(fs2.io.file.writeAll(path, blocker))
        }
        .compile
        .drain

    def replay: F[Unit] =
      Stream
        .resource(Blocker[F])
        .flatMap { blocker =>
          fs2.io.file
            .readAll(path, blocker, 5000)
            .through(fs2.text.utf8Decode)
            .through(fs2.text.lines)
            .evalMap { str =>
              topic
                .fromJsonStr(str)
                .leftMap(err => println(s"decode json error: ${err.getMessage}"))
                .toOption
                .traverse(cr => topic.producer.send(cr.toNJProducerRecord))
            }
        }
        .compile
        .drain
  }
}
