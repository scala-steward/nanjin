package com.github.chenharryhua.nanjin.kafka

import java.util.Properties

import akka.actor.ActorSystem
import akka.kafka.{
  CommitterSettings => AkkaCommitterSettings,
  ConsumerSettings  => AkkaConsumerSettings,
  ProducerSettings  => AkkaProducerSettings
}
import cats.Eval
import cats.effect.{ConcurrentEffect, ContextShift, IO, Sync, Timer}
import com.github.chenharryhua.nanjin.common.NJRootPath
import com.github.chenharryhua.nanjin.utils
import eu.timepit.refined.auto._
import fs2.kafka.{
  ConsumerSettings => Fs2ConsumerSettings,
  Deserializer     => Fs2Deserializer,
  ProducerSettings => Fs2ProducerSettings,
  Serializer       => Fs2Serializer
}
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig
import monocle.Traversal
import monocle.function.At.at
import monocle.macros.Lenses
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.security.auth.SecurityProtocol
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, Serializer}
import org.apache.kafka.streams.StreamsConfig

import scala.util.Try
import cats.Show

@Lenses final case class KafkaConsumerSettings(config: Map[String, String]) {

  def fs2ConsumerSettings[F[_]: Sync]: Fs2ConsumerSettings[F, Array[Byte], Array[Byte]] =
    Fs2ConsumerSettings[F, Array[Byte], Array[Byte]](
      Fs2Deserializer.delegate(new ByteArrayDeserializer),
      Fs2Deserializer.delegate(new ByteArrayDeserializer)).withProperties(config)

  def akkaConsumerSettings(system: ActorSystem): AkkaConsumerSettings[Array[Byte], Array[Byte]] =
    AkkaConsumerSettings[Array[Byte], Array[Byte]](
      system,
      new ByteArrayDeserializer,
      new ByteArrayDeserializer).withProperties(config)

  def akkaCommitterSettings(system: ActorSystem): AkkaCommitterSettings =
    AkkaCommitterSettings(system)

  val consumerProperties: Properties = utils.toProperties(config)
}

@Lenses final case class KafkaProducerSettings(config: Map[String, String]) {

  def fs2ProducerSettings[F[_]: Sync, K, V](
    kser: Serializer[K],
    vser: Serializer[V]): Fs2ProducerSettings[F, K, V] =
    Fs2ProducerSettings[F, K, V](Fs2Serializer.delegate(kser), Fs2Serializer.delegate(vser))
      .withProperties(config)

  def akkaProducerSettings[K, V](
    system: ActorSystem,
    kser: Serializer[K],
    vser: Serializer[V]): AkkaProducerSettings[K, V] =
    AkkaProducerSettings[K, V](system, kser, vser).withProperties(config)

  val producerProperties: Properties = utils.toProperties(config)
}

@Lenses final case class KafkaStreamSettings(config: Map[String, String]) {
  val streamProperties: Properties = utils.toProperties(config)
}

@Lenses final case class SharedAdminSettings(config: Map[String, String]) {
  val adminProperties: Properties = utils.toProperties(config)
}

@Lenses final case class SchemaRegistrySettings(config: Map[String, String])

@Lenses final case class KafkaSettings(
  consumerSettings: KafkaConsumerSettings,
  producerSettings: KafkaProducerSettings,
  streamSettings: KafkaStreamSettings,
  sharedAdminSettings: SharedAdminSettings,
  schemaRegistrySettings: SchemaRegistrySettings,
  rootPath: NJRootPath) {
  val appId: Option[String] = streamSettings.config.get(StreamsConfig.APPLICATION_ID_CONFIG)

  private def updateAll(key: String, value: String): KafkaSettings =
    Traversal
      .applyN[KafkaSettings, Map[String, String]](
        KafkaSettings.consumerSettings.composeLens(KafkaConsumerSettings.config),
        KafkaSettings.producerSettings.composeLens(KafkaProducerSettings.config),
        KafkaSettings.streamSettings.composeLens(KafkaStreamSettings.config),
        KafkaSettings.sharedAdminSettings.composeLens(SharedAdminSettings.config)
      )
      .composeLens(at(key))
      .set(Some(value))(this)

  def withBrokers(bs: String): KafkaSettings =
    updateAll(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bs)

  def withSaslJaas(sj: String): KafkaSettings =
    updateAll(SaslConfigs.SASL_JAAS_CONFIG, sj)

  def withSecurityProtocol(sp: SecurityProtocol): KafkaSettings =
    updateAll(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, sp.name)

  def withSchemaRegistryProperty(key: String, value: String): KafkaSettings =
    KafkaSettings.schemaRegistrySettings
      .composeLens(SchemaRegistrySettings.config)
      .composeLens(at(key))
      .set(Some(value))(this)

  def withSchemaRegistryUrl(url: String): KafkaSettings =
    withSchemaRegistryProperty(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, url)

  def withProducerProperty(key: String, value: String): KafkaSettings =
    KafkaSettings.producerSettings
      .composeLens(KafkaProducerSettings.config)
      .composeLens(at(key))
      .set(Some(value))(this)

  def withConsumerProperty(key: String, value: String): KafkaSettings =
    KafkaSettings.consumerSettings
      .composeLens(KafkaConsumerSettings.config)
      .composeLens(at(key))
      .set(Some(value))(this)

  def withStreamingProperty(key: String, value: String): KafkaSettings =
    KafkaSettings.streamSettings
      .composeLens(KafkaStreamSettings.config)
      .composeLens(at(key))
      .set(Some(value))(this)

  def withGroupId(gid: String): KafkaSettings =
    withConsumerProperty(ConsumerConfig.GROUP_ID_CONFIG, gid)

  def withApplicationId(appId: String): KafkaSettings =
    withStreamingProperty(StreamsConfig.APPLICATION_ID_CONFIG, appId)

  def withRootPath(rp: NJRootPath): KafkaSettings =
    KafkaSettings.rootPath.set(rp)(this)

  def ioContext(implicit contextShift: ContextShift[IO], timer: Timer[IO]): IoKafkaContext =
    new IoKafkaContext(this)

  def zioContext(
    implicit contextShift: ContextShift[zio.Task],
    timer: Timer[zio.Task],
    ce: ConcurrentEffect[zio.Task]) =
    new ZioKafkaContext(this)

  def monixContext(
    implicit contextShift: ContextShift[monix.eval.Task],
    timer: Timer[monix.eval.Task],
    ce: ConcurrentEffect[monix.eval.Task]) =
    new MonixKafkaContext(this)
}

object KafkaSettings {
  import cats.instances.map.catsStdShowForMap
  import cats.instances.string.catsStdShowForString
  implicit val showKafkaSettings: Show[KafkaSettings] = cats.derived.semi.show[KafkaSettings]
  
  private val defaultRootPath: NJRootPath             = NJRootPath("./data/kafka/")

  val empty: KafkaSettings = KafkaSettings(
    KafkaConsumerSettings(Map.empty),
    KafkaProducerSettings(Map.empty),
    KafkaStreamSettings(Map.empty),
    SharedAdminSettings(Map.empty),
    SchemaRegistrySettings(Map.empty),
    defaultRootPath
  )

  val local: KafkaSettings =
    KafkaSettings(
      KafkaConsumerSettings(
        Map(
          ConsumerConfig.MAX_POLL_RECORDS_CONFIG -> "100",
          ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest")),
      KafkaProducerSettings(Map.empty),
      KafkaStreamSettings(Map.empty),
      SharedAdminSettings(Map.empty),
      SchemaRegistrySettings(Map.empty),
      defaultRootPath
    ).withGroupId("nanjin-group")
      .withApplicationId("nanjin-app")
      .withBrokers("localhost:9092")
      .withSchemaRegistryUrl("http://localhost:8081")
      .withSecurityProtocol(SecurityProtocol.PLAINTEXT)
}
