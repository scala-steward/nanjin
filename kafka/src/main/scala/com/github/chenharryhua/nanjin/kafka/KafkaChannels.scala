package com.github.chenharryhua.nanjin.kafka

import akka.kafka.{
  CommitterSettings,
  ConsumerSettings => AkkaConsumerSettings,
  ProducerSettings => AkkaProducerSettings
}
import akka.stream.ActorMaterializer
import cats.Show
import cats.data.Reader
import cats.effect._
import cats.implicits._
import fs2.kafka.{ConsumerSettings => Fs2ConsumerSettings, ProducerSettings => Fs2ProducerSettings}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.streams.kstream.GlobalKTable

import scala.concurrent.Future
import scala.util.{Success, Try}

object Fs2Channel {
  implicit def showFs2Channel[F[_], K, V]: Show[Fs2Channel[F, K, V]] = _.show
}
final case class Fs2Channel[F[_]: ConcurrentEffect: ContextShift: Timer, K, V](
  topicDef: TopicDef[K, V],
  producerSettings: Fs2ProducerSettings[F, K, V],
  consumerSettings: Fs2ConsumerSettings[F, Array[Byte], Array[Byte]],
  keySerde: KeySerde[K],
  valueSerde: ValueSerde[V]
) extends Fs2MessageBitraverse with Serializable {
  import fs2.Stream
  import fs2.kafka._

  val decoder: KafkaMessageDecoder[CommittableConsumerRecord[F, ?, ?], K, V] =
    decoders.fs2MessageDecoder[F, K, V](topicDef.topicName, keySerde, valueSerde)

  val encoder: encoders.Fs2MessageEncoder[F, K, V] =
    encoders.fs2MessageEncoder[F, K, V](topicDef.topicName)

  def updateProducerSettings(
    f: Fs2ProducerSettings[F, K, V] => Fs2ProducerSettings[F, K, V]): Fs2Channel[F, K, V] =
    copy(producerSettings = f(producerSettings))

  def updateConsumerSettings(
    f: Fs2ConsumerSettings[F, Array[Byte], Array[Byte]] => Fs2ConsumerSettings[
      F,
      Array[Byte],
      Array[Byte]]): Fs2Channel[F, K, V] =
    copy(consumerSettings = f(consumerSettings))

  val producerStream: Stream[F, KafkaProducer[F, K, V]] =
    fs2.kafka.producerStream[F, K, V](producerSettings)

  val transactionalProducerStream: Stream[F, TransactionalKafkaProducer[F, K, V]] =
    fs2.kafka.transactionalProducerStream[F, K, V](producerSettings)

  val consume: Stream[F, CommittableConsumerRecord[F, Array[Byte], Array[Byte]]] =
    consumerStream[F, Array[Byte], Array[Byte]](consumerSettings)
      .evalTap(_.subscribeTo(topicDef.topicName))
      .flatMap(_.stream)

  val consumeNativeMessages: Stream[F, CommittableConsumerRecord[F, Try[K], Try[V]]] =
    consume.map(decoder.decodeMessage)

  val consumeMessages: Stream[F, Try[CommittableConsumerRecord[F, K, V]]] =
    consume.map(decoder.decodeBoth)

  val consumeValidMessages: Stream[F, CommittableConsumerRecord[F, K, V]] =
    consumeMessages.collect { case Success(x) => x }

  val consumeValues: Stream[F, Try[CommittableConsumerRecord[F, Array[Byte], V]]] =
    consume.map(decoder.decodeValue)

  val consumeValidValues: Stream[F, CommittableConsumerRecord[F, Array[Byte], V]] =
    consumeValues.collect { case Success(x) => x }

  val consumeKeys: Stream[F, Try[CommittableConsumerRecord[F, K, Array[Byte]]]] =
    consume.map(decoder.decodeKey)

  val consumeValidKeys: Stream[F, CommittableConsumerRecord[F, K, Array[Byte]]] =
    consumeKeys.collect { case Success(x) => x }

  val show: String =
    s"""
       |fs2 consumer runtime settings:
       |${consumerSettings.show}
       |${consumerSettings.properties.show}
       |
       |fs2 producer runtime settings:
       |${producerSettings.show}
       |${producerSettings.properties.show}""".stripMargin
}

object AkkaChannel {
  implicit def showAkkaChannel[F[_], K, V]: Show[AkkaChannel[F, K, V]] = _.show
}
final case class AkkaChannel[F[_]: ContextShift, K, V] private[kafka] (
  topicDef: TopicDef[K, V],
  producerSettings: AkkaProducerSettings[K, V],
  consumerSettings: AkkaConsumerSettings[Array[Byte], Array[Byte]],
  committerSettings: CommitterSettings,
  keySerde: KeySerde[K],
  valueSerde: ValueSerde[V])(implicit val materializer: ActorMaterializer)
    extends AkkaMessageBitraverse with Serializable {
  import akka.Done
  import akka.kafka.ConsumerMessage.CommittableMessage
  import akka.kafka.ProducerMessage.Envelope
  import akka.kafka.scaladsl.{Committer, Consumer, Producer}
  import akka.kafka.{ConsumerMessage, Subscriptions}
  import akka.stream.scaladsl.{Sink, Source}

  def updateProducerSettings(
    f: AkkaProducerSettings[K, V] => AkkaProducerSettings[K, V]): AkkaChannel[F, K, V] =
    copy(producerSettings = f(producerSettings))

  def updateConsumerSettings(
    f: AkkaConsumerSettings[Array[Byte], Array[Byte]] => AkkaConsumerSettings[
      Array[Byte],
      Array[Byte]]): AkkaChannel[F, K, V] =
    copy(consumerSettings = f(consumerSettings))

  def updateCommitterSettings(f: CommitterSettings => CommitterSettings): AkkaChannel[F, K, V] =
    copy(committerSettings = f(committerSettings))

  val decoder: KafkaMessageDecoder[CommittableMessage, K, V] =
    decoders.akkaMessageDecoder[K, V](topicDef.topicName, keySerde, valueSerde)

  val encoder: encoders.AkkaMessageEncoder[K, V] =
    encoders.akkaMessageEncoder[K, V](topicDef.topicName)

  val produceSink: Sink[Envelope[K, V, ConsumerMessage.Committable], Future[Done]] =
    Producer.committableSink(producerSettings)

  val commitSink: Sink[ConsumerMessage.Committable, Future[Done]] =
    Committer.sink(committerSettings)

  def assign(tps: Map[TopicPartition, Long])
    : Source[ConsumerRecord[Array[Byte], Array[Byte]], Consumer.Control] =
    Consumer.plainSource(consumerSettings, Subscriptions.assignmentWithOffset(tps))

  val consume: Source[CommittableMessage[Array[Byte], Array[Byte]], Consumer.Control] =
    Consumer.committableSource(consumerSettings, Subscriptions.topics(topicDef.topicName))

  val consumeNativeMessages: Source[CommittableMessage[Try[K], Try[V]], Consumer.Control] =
    consume.map(decoder.decodeMessage)

  val consumeMessages: Source[Try[CommittableMessage[K, V]], Consumer.Control] =
    consume.map(decoder.decodeBoth)

  val consumeValidMessages: Source[CommittableMessage[K, V], Consumer.Control] =
    consumeMessages.collect { case Success(x) => x }

  val consumeValues: Source[Try[CommittableMessage[Array[Byte], V]], Consumer.Control] =
    consume.map(decoder.decodeValue)

  val consumeValidValues: Source[CommittableMessage[Array[Byte], V], Consumer.Control] =
    consumeValues.collect { case Success(x) => x }

  val consumeKeys: Source[Try[CommittableMessage[K, Array[Byte]]], Consumer.Control] =
    consume.map(decoder.decodeKey)

  val consumeValidKeys: Source[CommittableMessage[K, Array[Byte]], Consumer.Control] =
    consumeKeys.collect { case Success(x) => x }

  val show: String =
    s"""
       |akka consumer runtime settings:
       |${consumerSettings.toString()}
       |
       |akka producer runtime settings:
       |${producerSettings.toString()}
     """.stripMargin
}

final case class StreamingChannel[K, V](
  topicDef: TopicDef[K, V],
  keySerde: KeySerde[K],
  valueSerde: ValueSerde[V])
    extends Serializable {
  import org.apache.kafka.streams.scala.StreamsBuilder
  import org.apache.kafka.streams.scala.kstream.{Consumed, KStream, KTable}

  val kstream: Reader[StreamsBuilder, KStream[K, V]] =
    Reader(
      builder => builder.stream[K, V](topicDef.topicName)(Consumed.`with`(keySerde, valueSerde)))

  val ktable: Reader[StreamsBuilder, KTable[K, V]] =
    Reader(
      builder => builder.table[K, V](topicDef.topicName)(Consumed.`with`(keySerde, valueSerde)))

  val gktable: Reader[StreamsBuilder, GlobalKTable[K, V]] =
    Reader(builder =>
      builder.globalTable[K, V](topicDef.topicName)(Consumed.`with`(keySerde, valueSerde)))

  def ktable(store: KafkaStore.InMemory[K, V]): Reader[StreamsBuilder, KTable[K, V]] =
    Reader(
      builder =>
        builder.table[K, V](topicDef.topicName, store.materialized)(
          Consumed.`with`(keySerde, valueSerde)))

  def ktable(store: KafkaStore.Persistent[K, V]): Reader[StreamsBuilder, KTable[K, V]] =
    Reader(
      builder =>
        builder.table[K, V](topicDef.topicName, store.materialized)(
          Consumed.`with`(keySerde, valueSerde)))
}
