package com.github.chenharryhua.nanjin.kafka

import akka.stream.ActorMaterializer
import cats.Show
import cats.data.Reader
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import cats.implicits._
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.streams.kstream.GlobalKTable

import scala.concurrent.Future
import scala.util.{Success, Try}

final class Fs2Channel[F[_]: ConcurrentEffect: ContextShift: Timer, K, V](
  topicDef: TopicDef[K, V],
  fs2Settings: Fs2Settings,
  keySerde: KeySerde[K],
  valueSerde: ValueSerde[V]
) extends Fs2MessageBitraverse with Serializable {
  import fs2.kafka._
  import fs2.{Pipe, Stream}

  val decoder: KafkaMessageDecoder[CommittableMessage[F, ?, ?], K, V] =
    decoders.fs2MessageDecoder[F, K, V](topicDef.topicName, keySerde, valueSerde)

  val encoder: encoders.Fs2MessageEncoder[F, K, V] =
    encoders.fs2MessageEncoder[F, K, V](topicDef.topicName)

  val producerSettings: ProducerSettings[K, V] =
    fs2Settings.producerSettings(keySerde.serializer, valueSerde.serializer)

  val consumerSettings: ConsumerSettings[Array[Byte], Array[Byte]] = fs2Settings.consumerSettings

  val producerStream: Stream[F, KafkaProducer[F, K, V]] =
    fs2.kafka.producerStream[F, K, V](producerSettings)

  def sink[G[+_]]: Pipe[F, ProducerMessage[G, K, V, Option[CommittableOffset[F]]], Unit] =
    (in: Stream[F, ProducerMessage[G, K, V, Option[CommittableOffset[F]]]]) =>
      Stream.eval(producerResource[F, K, V](producerSettings).use { producer =>
        in.evalMap { kvo =>
          producer.produce[G, Option[CommittableOffset[F]]](kvo).flatMap(_.map(_.passthrough))
        }.through(commitBatchOption).compile.drain
      })

  val decode: Pipe[
    F,
    CommittableMessage[F, Array[Byte], Array[Byte]],
    CommittableMessage[F, Try[K], Try[V]]] =
    _.map(decoder.decodeMessage)

  val consume: Stream[F, CommittableMessage[F, Array[Byte], Array[Byte]]] =
    consumerStream[F, Array[Byte], Array[Byte]](consumerSettings)
      .evalTap(_.subscribeTo(topicDef.topicName))
      .flatMap(_.stream)

  val consumeNativeMessages: Stream[F, CommittableMessage[F, Try[K], Try[V]]] =
    consume.map(decoder.decodeMessage)

  val consumeMessages: Stream[F, Try[CommittableMessage[F, K, V]]] =
    consume.map(decoder.decodeBoth)

  val consumeValidMessages: Stream[F, CommittableMessage[F, K, V]] =
    consumeMessages.collect { case Success(x) => x }

  val consumeValues: Stream[F, Try[CommittableMessage[F, Array[Byte], V]]] =
    consume.map(decoder.decodeValue)

  val consumeValidValues: Stream[F, CommittableMessage[F, Array[Byte], V]] =
    consumeValues.collect { case Success(x) => x }

  val consumeKeys: Stream[F, Try[CommittableMessage[F, K, Array[Byte]]]] =
    consume.map(decoder.decodeKey)

  val consumeValidKeys: Stream[F, CommittableMessage[F, K, Array[Byte]]] =
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

object Fs2Channel {
  implicit def showFs2Channel[F[_], K, V]: Show[Fs2Channel[F, K, V]] = _.show
}

final class AkkaChannel[K, V](
  topicDef: TopicDef[K, V],
  akkaSettings: AkkaSettings,
  keySerde: KeySerde[K],
  valueSerde: ValueSerde[V]
)(implicit val materializer: ActorMaterializer)
    extends AkkaMessageBitraverse with Serializable {
  import akka.kafka.ConsumerMessage.CommittableMessage
  import akka.kafka.ProducerMessage.Envelope
  import akka.kafka.scaladsl.{Committer, Consumer, Producer}
  import akka.kafka.{ConsumerMessage, ConsumerSettings, ProducerSettings, Subscriptions}
  import akka.stream.scaladsl.{Flow, Sink, Source}
  import akka.{Done, NotUsed}

  val decoder: KafkaMessageDecoder[CommittableMessage, K, V] =
    decoders.akkaMessageDecoder[K, V](topicDef.topicName, keySerde, valueSerde)

  val encoder: encoders.AkkaMessageEncoder[K, V] =
    encoders.akkaMessageEncoder[K, V](topicDef.topicName)

  val consumerSettings: ConsumerSettings[Array[Byte], Array[Byte]] =
    akkaSettings.consumerSettings(materializer.system)

  val producerSettings: ProducerSettings[K, V] =
    akkaSettings.producerSettings(materializer.system, keySerde.serializer, valueSerde.serializer)

  val committableSink: Sink[Envelope[K, V, ConsumerMessage.Committable], Future[Done]] =
    Producer.committableSink(producerSettings)

  val sink: Sink[ConsumerMessage.Committable, Future[Done]] =
    Committer.sink(akkaSettings.committerSettings(materializer.system))

  def assign(tps: Map[TopicPartition, Long])
    : Source[ConsumerRecord[Array[Byte], Array[Byte]], Consumer.Control] =
    Consumer.plainSource(consumerSettings, Subscriptions.assignmentWithOffset(tps))

  val decode: Flow[
    CommittableMessage[Array[Byte], Array[Byte]],
    CommittableMessage[Try[K], Try[V]],
    NotUsed] =
    Flow.fromFunction(decoder.decodeMessage)

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

object AkkaChannel {
  implicit def showAkkaChannel[K, V]: Show[AkkaChannel[K, V]] = _.show
}

final class StreamingChannel[K, V](
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
