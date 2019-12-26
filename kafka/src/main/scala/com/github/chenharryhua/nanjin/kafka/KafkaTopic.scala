package com.github.chenharryhua.nanjin.kafka

import cats.effect.Resource
import cats.implicits._
import com.github.chenharryhua.nanjin.codec._
import com.sksamuel.avro4s.{Record, ToRecord}
import io.circe.{Error, Json}
import io.circe.parser.decode
import io.circe.syntax._
import monocle.function.At
import org.apache.avro.Schema
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.{Deserializer, Serializer}
import org.apache.kafka.streams.processor.{RecordContext, TopicNameExtractor}

final class TopicCodec[K, V] private[kafka] (
  val keyCodec: KafkaCodec.Key[K],
  val valueCodec: KafkaCodec.Value[V]) {
  require(
    keyCodec.topicName === valueCodec.topicName,
    "key and value codec should have same topic name")
  val keySerde: KafkaSerde.Key[K]        = keyCodec.serde
  val valueSerde: KafkaSerde.Value[V]    = valueCodec.serde
  val keySchema: Schema                  = keySerde.schema
  val valueSchema: Schema                = valueSerde.schema
  val keySerializer: Serializer[K]       = keySerde.serializer
  val keyDeserializer: Deserializer[K]   = keySerde.deserializer
  val valueSerializer: Serializer[V]     = valueSerde.serializer
  val valueDeserializer: Deserializer[V] = valueSerde.deserializer
}

final class KafkaTopic[F[_], K, V] private[kafka] (
  val topicDef: TopicDef[K, V],
  val context: KafkaContext[F])
    extends TopicNameExtractor[K, V] {
  import context.{concurrentEffect, contextShift, timer}
  import topicDef.{
    avroKeyEncoder,
    avroValueEncoder,
    jsonKeyDecoder,
    jsonKeyEncoder,
    jsonValueDecoder,
    jsonValueEncoder,
    serdeOfKey,
    serdeOfValue,
    showKey,
    showValue
  }

  val consumerGroupId: Option[KafkaConsumerGroupId] =
    KafkaConsumerSettings.config
      .composeLens(At.at(ConsumerConfig.GROUP_ID_CONFIG))
      .get(context.settings.consumerSettings)
      .map(KafkaConsumerGroupId)

  override def extract(key: K, value: V, rc: RecordContext): String = topicDef.topicName

  val codec: TopicCodec[K, V] = new TopicCodec(
    serdeOfKey.asKey(context.settings.schemaRegistrySettings.config).codec(topicDef.topicName),
    serdeOfValue.asValue(context.settings.schemaRegistrySettings.config).codec(topicDef.topicName)
  )

  def decoder[G[_, _]: NJConsumerMessage](
    cr: G[Array[Byte], Array[Byte]]): KafkaGenericDecoder[G, K, V] =
    new KafkaGenericDecoder[G, K, V](cr, codec.keyCodec, codec.valueCodec)

  private val toAvroRecord: ToRecord[NJConsumerRecord[K, V]] =
    ToRecord[NJConsumerRecord[K, V]](topicDef.njConsumerRecordSchema)

  def toAvro[G[_, _]: NJConsumerMessage](cr: G[Array[Byte], Array[Byte]]): Record =
    toAvroRecord.to(decoder(cr).record)

  def toJson[G[_, _]: NJConsumerMessage](cr: G[Array[Byte], Array[Byte]]): Json =
    decoder(cr).record.asJson

  def fromJson(jsonString: String): Either[Error, NJConsumerRecord[K, V]] =
    decode[NJConsumerRecord[K, V]](jsonString)

  //channels
  val fs2Channel: KafkaChannels.Fs2Channel[F, K, V] =
    new KafkaChannels.Fs2Channel[F, K, V](
      topicDef.topicName,
      context.settings.producerSettings
        .fs2ProducerSettings(codec.keySerializer, codec.valueSerializer),
      context.settings.consumerSettings.fs2ConsumerSettings)

  val akkaResource: Resource[F, KafkaChannels.AkkaChannel[F, K, V]] = Resource.make(
    concurrentEffect.delay(
      new KafkaChannels.AkkaChannel[F, K, V](
        topicDef.topicName,
        context.settings.producerSettings.akkaProducerSettings(
          context.akkaSystem.value,
          codec.keySerializer,
          codec.valueSerializer),
        context.settings.consumerSettings.akkaConsumerSettings(context.akkaSystem.value),
        context.settings.consumerSettings.akkaCommitterSettings(context.akkaSystem.value))))(_ =>
    concurrentEffect.unit)

  val kafkaStream: KafkaChannels.StreamingChannel[K, V] =
    new KafkaChannels.StreamingChannel[K, V](topicDef.topicName, codec.keySerde, codec.valueSerde)

  // APIs
  val schemaRegistry: KafkaSchemaRegistryApi[F] = KafkaSchemaRegistryApi[F](this)
  val admin: KafkaTopicAdminApi[F]              = KafkaTopicAdminApi[F, K, V](this)
  val consumer: KafkaConsumerApi[F, K, V]       = KafkaConsumerApi[F, K, V](this)
  val producer: KafkaProducerApi[F, K, V]       = KafkaProducerApi[F, K, V](this)
  val monitor: KafkaMonitoringApi[F, K, V]      = KafkaMonitoringApi[F, K, V](this)
}
