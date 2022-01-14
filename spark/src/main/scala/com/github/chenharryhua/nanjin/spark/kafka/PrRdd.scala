package com.github.chenharryhua.nanjin.spark.kafka

import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.{ProducerMessage, ProducerSettings as AkkaProducerSettings}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import cats.effect.kernel.{Async, Sync}
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.common.ChunkSize
import com.github.chenharryhua.nanjin.datetime.NJDateTimeRange
import com.github.chenharryhua.nanjin.kafka.{akkaUpdater, fs2Updater, KafkaTopic}
import com.github.chenharryhua.nanjin.spark.*
import com.github.chenharryhua.nanjin.spark.persist.RddAvroFileHoarder
import fs2.Stream
import fs2.interop.reactivestreams.*
import fs2.kafka.{ProducerRecords, ProducerResult, ProducerSettings as Fs2ProducerSettings}
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.spark.rdd.RDD
import squants.information.Information

import scala.concurrent.duration.FiniteDuration

final class PrRdd[F[_], K, V] private[kafka] (
  val rdd: RDD[NJProducerRecord[K, V]],
  topic: KafkaTopic[F, K, V],
  cfg: SKConfig
) extends Serializable {

  // config
  private def updateCfg(f: SKConfig => SKConfig): PrRdd[F, K, V] =
    new PrRdd[F, K, V](rdd, topic, f(cfg))

  def withInterval(ms: FiniteDuration): PrRdd[F, K, V]  = updateCfg(_.loadInterval(ms))
  def withRecordsLimit(num: Long): PrRdd[F, K, V]       = updateCfg(_.loadRecordsLimit(num))
  def withTimeLimit(fd: FiniteDuration): PrRdd[F, K, V] = updateCfg(_.loadTimeLimit(fd))

  // transform
  def transform(f: RDD[NJProducerRecord[K, V]] => RDD[NJProducerRecord[K, V]]): PrRdd[F, K, V] =
    new PrRdd[F, K, V](f(rdd), topic, cfg)

  def partitionOf(num: Int): PrRdd[F, K, V] = transform(_.filter(_.partition.exists(_ === num)))

  def offsetRange(start: Long, end: Long): PrRdd[F, K, V] = transform(range.pr.offset(start, end))
  def timeRange(dr: NJDateTimeRange): PrRdd[F, K, V]      = transform(range.pr.timestamp(dr))

  def ascendTimestamp: PrRdd[F, K, V]  = transform(sort.ascend.pr.timestamp)
  def descendTimestamp: PrRdd[F, K, V] = transform(sort.descend.pr.timestamp)
  def ascendOffset: PrRdd[F, K, V]     = transform(sort.ascend.pr.offset)
  def descendOffset: PrRdd[F, K, V]    = transform(sort.descend.pr.offset)

  def noTimestamp: PrRdd[F, K, V] = transform(_.map(_.noTimestamp))
  def noPartition: PrRdd[F, K, V] = transform(_.map(_.noPartition))
  def noMeta: PrRdd[F, K, V]      = transform(_.map(_.noMeta))

  def replicate(num: Int): PrRdd[F, K, V] =
    transform(rdd => (1 until num).foldLeft(rdd) { case (r, _) => r.union(rdd) })

  def normalize: PrRdd[F, K, V] = transform(_.map(NJProducerRecord.avroCodec(topic.topicDef).idConversion))

  // actions

  def count(implicit F: Sync[F]): F[Long] = F.delay(rdd.count())

  def save: RddAvroFileHoarder[F, NJProducerRecord[K, V]] =
    new RddAvroFileHoarder[F, NJProducerRecord[K, V]](rdd, NJProducerRecord.avroCodec(topic.topicDef).avroEncoder)

  def upload: Fs2Upload[F, K, V] = new Fs2Upload[F, K, V](rdd, topic, cfg, fs2Updater.unitProducer[F, K, V])
  def upload(akkaSystem: ActorSystem): AkkaUpload[F, K, V] =
    new AkkaUpload[F, K, V](rdd, akkaSystem, topic, cfg, akkaUpdater.unitProducer[K, V])
}

final class Fs2Upload[F[_], K, V] private[kafka] (
  rdd: RDD[NJProducerRecord[K, V]],
  topic: KafkaTopic[F, K, V],
  cfg: SKConfig,
  fs2Producer: fs2Updater.Producer[F, K, V]
) extends Serializable {

  def updateProducer(f: Fs2ProducerSettings[F, K, V] => Fs2ProducerSettings[F, K, V]): Fs2Upload[F, K, V] =
    new Fs2Upload[F, K, V](rdd, topic, cfg, fs2Producer.updateConfig(f))

  def toTopic(topic: KafkaTopic[F, K, V]): Fs2Upload[F, K, V] =
    new Fs2Upload[F, K, V](rdd, topic, cfg, fs2Producer)

  def withChunkSize(cs: ChunkSize): Fs2Upload[F, K, V] =
    new Fs2Upload[F, K, V](rdd, topic, cfg.loadChunkSize(cs), fs2Producer)

  def stream(implicit ce: Async[F]): Stream[F, ProducerResult[Unit, K, V]] = {
    val params: SKParams = cfg.evalConfig
    rdd
      .stream[F](params.loadParams.chunkSize)
      .interruptAfter(params.loadParams.timeLimit)
      .take(params.loadParams.recordsLimit)
      .chunkN(params.loadParams.chunkSize.value)
      .map(chk => ProducerRecords(chk.map(_.toFs2ProducerRecord(topic.topicName.value))))
      .metered(params.loadParams.interval)
      .through(topic.fs2Channel.updateProducer(fs2Producer.updates.run).producerPipe)
  }
}

final class AkkaUpload[F[_], K, V] private[kafka] (
  rdd: RDD[NJProducerRecord[K, V]],
  akkaSystem: ActorSystem,
  topic: KafkaTopic[F, K, V],
  cfg: SKConfig,
  akkaProducer: akkaUpdater.Producer[K, V]
) extends Serializable {

  def updateProducer(f: AkkaProducerSettings[K, V] => AkkaProducerSettings[K, V]): AkkaUpload[F, K, V] =
    new AkkaUpload[F, K, V](rdd, akkaSystem, topic, cfg, akkaProducer.updateConfig(f))

  def toTopic(topic: KafkaTopic[F, K, V]): AkkaUpload[F, K, V] =
    new AkkaUpload[F, K, V](rdd, akkaSystem, topic, cfg, akkaProducer)

  def withThrottle(bytes: Information): AkkaUpload[F, K, V] =
    new AkkaUpload[F, K, V](rdd, akkaSystem, topic, cfg.loadThrottle(bytes), akkaProducer)

  def withChunkSize(cs: ChunkSize): AkkaUpload[F, K, V] =
    new AkkaUpload[F, K, V](rdd, akkaSystem, topic, cfg.loadChunkSize(cs), akkaProducer)

  def stream(implicit F: Async[F]): Stream[F, ProducerMessage.Results[K, V, NotUsed]] =
    Stream.resource {
      implicit val mat: Materializer = Materializer(akkaSystem)
      val params: SKParams           = cfg.evalConfig
      rdd.stream[F](params.loadParams.chunkSize).toUnicastPublisher.map { p =>
        Source
          .fromPublisher(p)
          .take(params.loadParams.recordsLimit)
          .takeWithin(params.loadParams.timeLimit)
          .map(m => ProducerMessage.single(m.toProducerRecord(topic.topicName.value)))
          .buffer(params.loadParams.chunkSize.value, OverflowStrategy.backpressure)
          .via(topic.akkaChannel(akkaSystem).updateProducer(akkaProducer.updates.run).flexiFlow)
          .throttle(
            params.loadParams.throttle.toBytes.toInt,
            params.loadParams.interval,
            {
              case ProducerMessage.Result(meta, _) => meta.serializedKeySize() + meta.serializedValueSize()
              case ProducerMessage.MultiResult(parts, _) =>
                parts.foldLeft(0) { case (sum, item) =>
                  val meta: RecordMetadata = item.metadata
                  sum + meta.serializedKeySize() + meta.serializedKeySize()
                }
              case ProducerMessage.PassThroughResult(_) => 0
            }
          )
          .runWith(Sink.asPublisher(fanout = false))
          .toStreamBuffered(params.loadParams.chunkSize.value)
      }
    }.flatten
}
