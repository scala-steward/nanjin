package com.github.chenharryhua.nanjin.spark.kafka

import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.ProducerMessage
import akka.stream.scaladsl.Sink
import akka.stream.{Materializer, OverflowStrategy}
import cats.effect.{ConcurrentEffect, ContextShift, Sync, Timer}
import cats.syntax.all._
import com.github.chenharryhua.nanjin.datetime.NJDateTimeRange
import com.github.chenharryhua.nanjin.kafka.KafkaTopic
import com.github.chenharryhua.nanjin.messages.kafka.codec.AvroCodec
import com.github.chenharryhua.nanjin.spark._
import com.github.chenharryhua.nanjin.spark.persist.RddAvroFileHoarder
import frameless.cats.implicits._
import fs2.Stream
import fs2.interop.reactivestreams._
import fs2.kafka.{ProducerRecords, ProducerResult}
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.spark.rdd.RDD

import scala.concurrent.duration.FiniteDuration

final class PrRdd[F[_], K, V] private[kafka] (
  val rdd: RDD[NJProducerRecord[K, V]],
  topic: KafkaTopic[F, K, V],
  cfg: SKConfig
) extends Serializable {

  val params: SKParams = cfg.evalConfig

  val codec: AvroCodec[NJProducerRecord[K, V]] = NJProducerRecord.avroCodec(topic.topicDef)

  // config
  private def updateCfg(f: SKConfig => SKConfig): PrRdd[F, K, V] =
    new PrRdd[F, K, V](rdd, topic, f(cfg))

  def withInterval(ms: FiniteDuration): PrRdd[F, K, V] = updateCfg(_.withLoadInterval(ms))
  def withBulkSize(num: Int): PrRdd[F, K, V]           = updateCfg(_.withLoadBulkSize(num))
  def withBatchSize(num: Int): PrRdd[F, K, V]          = updateCfg(_.withUploadBatchSize(num))
  def withBufferSize(num: Int): PrRdd[F, K, V]         = updateCfg(_.withLoadBufferSize(num))

  def withRecordsLimit(num: Long): PrRdd[F, K, V]       = updateCfg(_.withLoadRecordsLimit(num))
  def withTimeLimit(fd: FiniteDuration): PrRdd[F, K, V] = updateCfg(_.withLoadTimeLimit(fd))

  // transform
  def transform(f: RDD[NJProducerRecord[K, V]] => RDD[NJProducerRecord[K, V]]): PrRdd[F, K, V] =
    new PrRdd[F, K, V](f(rdd), topic, cfg)

  def partitionOf(num: Int): PrRdd[F, K, V] = transform(_.filter(_.partition.exists(_ === num)))

  def offsetRange(start: Long, end: Long): PrRdd[F, K, V] = transform(range.pr.offset(start, end))
  def timeRange(dr: NJDateTimeRange): PrRdd[F, K, V]      = transform(range.pr.timestamp(dr))
  def timeRange: PrRdd[F, K, V]                           = timeRange(params.timeRange)

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

  // maps
  def bimap[K2, V2](k: K => K2, v: V => V2)(other: KafkaTopic[F, K2, V2]): PrRdd[F, K2, V2] =
    new PrRdd[F, K2, V2](rdd.map(_.bimap(k, v)), other, cfg).normalize

  def map[K2, V2](f: NJProducerRecord[K, V] => NJProducerRecord[K2, V2])(
    other: KafkaTopic[F, K2, V2]): PrRdd[F, K2, V2] =
    new PrRdd[F, K2, V2](rdd.map(f), other, cfg).normalize

  def flatMap[K2, V2](f: NJProducerRecord[K, V] => TraversableOnce[NJProducerRecord[K2, V2]])(
    other: KafkaTopic[F, K2, V2]): PrRdd[F, K2, V2] =
    new PrRdd[F, K2, V2](rdd.flatMap(f), other, cfg).normalize

  // actions
  def upload(implicit
    ce: ConcurrentEffect[F],
    timer: Timer[F],
    cs: ContextShift[F]): Stream[F, ProducerResult[K, V, Unit]] =
    rdd
      .stream[F]
      .interruptAfter(params.loadParams.timeLimit)
      .take(params.loadParams.recordsLimit)
      .chunkN(params.loadParams.uploadBatchSize)
      .map(chk => ProducerRecords(chk.map(_.toFs2ProducerRecord(topic.topicName.value))))
      .buffer(params.loadParams.bufferSize)
      .metered(params.loadParams.interval)
      .through(topic.fs2Channel.producerPipe)

  def upload(akkaSystem: ActorSystem)(implicit
    F: ConcurrentEffect[F],
    cs: ContextShift[F]): Stream[F, ProducerMessage.Results[K, V, NotUsed]] =
    Stream.suspend {
      implicit val mat: Materializer = Materializer(akkaSystem)
      rdd
        .source[F]
        .take(params.loadParams.recordsLimit)
        .takeWithin(params.loadParams.timeLimit)
        .map(m => ProducerMessage.single(m.toProducerRecord(topic.topicName.value)))
        .buffer(params.loadParams.bufferSize, OverflowStrategy.backpressure)
        .via(topic.akkaChannel(akkaSystem).flexiFlow)
        .throttle(
          params.loadParams.bulkSize,
          params.loadParams.interval,
          {
            case ProducerMessage.Result(meta, _) => meta.serializedKeySize() + meta.serializedValueSize()
            case ProducerMessage.MultiResult(parts, _) =>
              parts.foldLeft(0) { case (sum, item) =>
                val meta: RecordMetadata = item.metadata
                sum + meta.serializedKeySize() + meta.serializedKeySize()
              }
            case ProducerMessage.PassThroughResult(_) => 1000
          }
        )
        .runWith(Sink.asPublisher(fanout = false))
        .toStream
    }

  def count(implicit F: Sync[F]): F[Long] = F.delay(rdd.count())

  def save: RddAvroFileHoarder[F, NJProducerRecord[K, V]] =
    new RddAvroFileHoarder[F, NJProducerRecord[K, V]](rdd, codec.avroEncoder)
}
