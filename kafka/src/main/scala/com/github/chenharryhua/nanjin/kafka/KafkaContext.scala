package com.github.chenharryhua.nanjin.kafka

import cats.Endo
import cats.data.Reader
import cats.effect.Resource
import cats.effect.kernel.{Async, Sync}
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.common.kafka.{TopicName, TopicNameL}
import com.github.chenharryhua.nanjin.common.{utils, UpdateConfig}
import com.github.chenharryhua.nanjin.kafka.streaming.{KafkaStreamsBuilder, StateStores}
import com.github.chenharryhua.nanjin.messages.kafka.codec.*
import fs2.kafka.*
import fs2.{Chunk, Pipe, Stream}
import io.circe.Json
import io.circe.jawn.parse
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.scala.StreamsBuilder

import scala.util.Try

final class KafkaContext[F[_]] private (val settings: KafkaSettings)
    extends UpdateConfig[KafkaSettings, KafkaContext[F]] with Serializable {

  override def updateConfig(f: Endo[KafkaSettings]): KafkaContext[F] =
    new KafkaContext[F](f(settings))

  def asKey[K: SerdeOf]: Serde[K]   = SerdeOf[K].asKey(settings.schemaRegistrySettings.config).serde
  def asValue[V: SerdeOf]: Serde[V] = SerdeOf[V].asValue(settings.schemaRegistrySettings.config).serde

  def asKey[K](avro: AvroCodec[K]): Serde[K] =
    SerdeOf[K](avro).asKey(settings.schemaRegistrySettings.config).serde
  def asValue[V](avro: AvroCodec[V]): Serde[V] =
    SerdeOf[V](avro).asValue(settings.schemaRegistrySettings.config).serde

  def topic[K, V](topicDef: TopicDef[K, V]): KafkaTopic[F, K, V] =
    new KafkaTopic[F, K, V](topicDef, settings)

  @transient lazy val schemaRegistry: SchemaRegistryApi[F] = {
    val url_config = AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG
    val url = settings.schemaRegistrySettings.config.get(url_config) match {
      case Some(value) => value
      case None        => throw new Exception(s"$url_config is absent")
    }
    val cacheCapacity: Int = settings.schemaRegistrySettings.config
      .get(AbstractKafkaSchemaSerDeConfig.MAX_SCHEMAS_PER_SUBJECT_CONFIG)
      .flatMap(s => Try(s.toInt).toOption)
      .getOrElse(AbstractKafkaSchemaSerDeConfig.MAX_SCHEMAS_PER_SUBJECT_DEFAULT)
    new SchemaRegistryApi[F](new CachedSchemaRegistryClient(url, cacheCapacity))
  }

  /*
   * consumer
   */

  def consumer[K, V](topicDef: TopicDef[K, V])(implicit F: Sync[F]): KafkaConsume[F, K, V] = {
    val serdePair: KeyValueSerdePair[K, V] =
      topicDef.rawSerdes.register(settings.schemaRegistrySettings, topicDef.topicName)
    new KafkaConsume[F, K, V](
      topicDef.topicName,
      ConsumerSettings[F, K, V](
        Deserializer.delegate[F, K](serdePair.key.serde.deserializer()),
        Deserializer.delegate[F, V](serdePair.value.serde.deserializer()))
        .withProperties(settings.consumerSettings.properties)
    )
  }

  def consumer(topicName: TopicName)(implicit F: Sync[F]): KafkaByteConsume[F] =
    new KafkaByteConsume[F](
      topicName,
      ConsumerSettings[F, Array[Byte], Array[Byte]](
        Deserializer[F, Array[Byte]],
        Deserializer[F, Array[Byte]]).withProperties(settings.consumerSettings.properties),
      schemaRegistry.fetchAvroSchema(topicName),
      settings.schemaRegistrySettings
    )

  def consumer(topicName: TopicNameL)(implicit F: Sync[F]): KafkaByteConsume[F] =
    consumer(TopicName(topicName))

  def monitor(topicName: TopicNameL, f: AutoOffsetReset.type => AutoOffsetReset = _.Latest)(implicit
    F: Async[F]): Stream[F, String] =
    Stream.eval(utils.randomUUID[F]).flatMap { uuid =>
      consumer(TopicName(topicName))
        .updateConfig( // avoid accidentally join an existing consumer-group
          _.withGroupId(uuid.show).withEnableAutoCommit(false).withAutoOffsetReset(f(AutoOffsetReset)))
        .genericRecords
        .map { ccr =>
          val rcd = ccr.record
          rcd.value
            .flatMap(gr2Jackson)
            .toEither
            .leftMap(e =>
              new Exception(s"topic=${rcd.topic}, partition=${rcd.partition}, offset=${rcd.offset}", e))
        }
        .rethrow
    }

  /*
   * producer
   */

  def producer[K: SerdeOf, V: SerdeOf](implicit F: Sync[F]): KafkaProduce[F, K, V] =
    new KafkaProduce[F, K, V](
      ProducerSettings[F, K, V](
        Serializer.delegate(asKey[K].serializer()),
        Serializer.delegate(asValue[V].serializer())).withProperties(settings.producerSettings.properties)
    )
  def producer[K, V](raw: RawKeyValueSerdePair[K, V])(implicit F: Sync[F]): KafkaProduce[F, K, V] =
    producer[K, V](raw.key, raw.value, Sync[F])

  private def bytesProducerSettings(implicit F: Sync[F]): ProducerSettings[F, Array[Byte], Array[Byte]] =
    ProducerSettings[F, Array[Byte], Array[Byte]](Serializer[F, Array[Byte]], Serializer[F, Array[Byte]])
      .withProperties(settings.producerSettings.properties)

  def sink(topicName: TopicName, f: Endo[PureProducerSettings])(implicit
    F: Async[F]): Pipe[F, Chunk[GenericRecord], ProducerResult[Array[Byte], Array[Byte]]] = {
    (ss: Stream[F, Chunk[GenericRecord]]) =>
      Stream.eval(schemaRegistry.fetchAvroSchema(topicName)).flatMap { skm =>
        val builder = new PushGenericRecord(settings.schemaRegistrySettings, topicName, skm)
        val prStream: Stream[F, ProducerRecords[Array[Byte], Array[Byte]]] =
          ss.map(_.map(builder.fromGenericRecord))

        KafkaProducer
          .pipe(bytesProducerSettings.withProperties(f(pureProducerSetting).properties))
          .apply(prStream)
      }
  }

  def sink(topicName: TopicNameL, f: Endo[PureProducerSettings])(implicit
    F: Async[F]): Pipe[F, Chunk[GenericRecord], ProducerResult[Array[Byte], Array[Byte]]] =
    sink(TopicName(topicName), f)

  def jacksonProduce(jackson: String)(implicit F: Async[F]): F[ProducerResult[Array[Byte], Array[Byte]]] =
    for {
      tn <- F.fromEither(parse(jackson).flatMap(_.hcursor.get[String]("topic")))
      topicName <- F.fromEither(TopicName.from(tn))
      schemaPair <- schemaRegistry.fetchAvroSchema(topicName)
      gr <- F.fromTry(jackson2GR(schemaPair.consumerSchema, jackson))
      builder = new PushGenericRecord(settings.schemaRegistrySettings, topicName, schemaPair)
      res <- KafkaProducer
        .resource(bytesProducerSettings)
        .use(_.produce(ProducerRecords.one(builder.fromGenericRecord(gr))).flatten)
    } yield res

  /** upload records which are downloaded from Confluent Kafka Control Center
    */
  def confluentProduce(confluent: String)(implicit
    F: Async[F]): F[List[ProducerResult[Array[Byte], Array[Byte]]]] = {
    def sendOne(json: Json): F[ProducerResult[Array[Byte], Array[Byte]]] = for {
      topicName <- F.fromEither(json.hcursor.get[String]("topic").flatMap(TopicName.from))
      schemaPair <- schemaRegistry.fetchAvroSchema(topicName)
      key <- F.fromTry(
        json.hcursor.get[Json]("key").toTry.flatMap(js => jackson2GR(schemaPair.key, js.noSpaces)))
      value <- F.fromTry(
        json.hcursor.get[Json]("value").toTry.flatMap(js => jackson2GR(schemaPair.value, js.noSpaces)))
      builder = new PushGenericRecord(settings.schemaRegistrySettings, topicName, schemaPair)
      pr <- KafkaProducer
        .resource(bytesProducerSettings)
        .use(_.produce(ProducerRecords.one(builder.fromGenericRecord(key, value))).flatten)
    } yield pr

    for {
      jsons <- F.fromEither(parse(confluent).flatMap(_.as[List[Json]]))
      prs <- jsons.traverse(sendOne)
    } yield prs
  }

  /*
   * kafka streaming
   */

  def store[K: SerdeOf, V: SerdeOf](storeName: TopicName): StateStores[K, V] =
    StateStores[K, V](
      storeName,
      settings.schemaRegistrySettings,
      RawKeyValueSerdePair[K, V](SerdeOf[K], SerdeOf[V]))

  def store[K: SerdeOf, V: SerdeOf](storeName: TopicNameL): StateStores[K, V] =
    store(TopicName(storeName))

  def buildStreams(applicationId: String, topology: Reader[StreamsBuilder, Unit])(implicit
    F: Async[F]): KafkaStreamsBuilder[F] =
    streaming.KafkaStreamsBuilder[F](applicationId, settings.streamSettings, topology)

  def buildStreams(applicationId: String, topology: StreamsBuilder => Unit)(implicit
    F: Async[F]): KafkaStreamsBuilder[F] =
    buildStreams(applicationId, Reader(topology))

  /*
   * admin topic
   */

  def admin(implicit F: Async[F]): Resource[F, KafkaAdminClient[F]] =
    KafkaAdminClient.resource[F](settings.adminSettings)

  def admin(topicName: TopicName)(implicit F: Async[F]): Resource[F, KafkaAdminApi[F]] =
    KafkaAdminApi[F](admin, topicName, settings.consumerSettings)

  def admin(topicName: TopicNameL)(implicit F: Async[F]): Resource[F, KafkaAdminApi[F]] =
    admin(TopicName(topicName))

  // pick up single record

  def cherryPick(topicName: TopicName, partition: Int, offset: Long)(implicit F: Async[F]): F[String] =
    admin(topicName).use(_.retrieveRecord(partition, offset).flatMap {
      case None => F.raiseError(new Exception("no record"))
      case Some(value) =>
        schemaRegistry.fetchAvroSchema(topicName).flatMap { schemaPair =>
          val pgr = new PullGenericRecord(settings.schemaRegistrySettings, topicName, schemaPair)
          F.fromTry(pgr.toGenericRecord(value).flatMap(gr2Jackson))
        }
    })

  def cherryPick(topicName: TopicNameL, partition: Int, offset: Long)(implicit F: Async[F]): F[String] =
    cherryPick(TopicName(topicName), partition, offset)
}

object KafkaContext {
  def apply[F[_]](settings: KafkaSettings): KafkaContext[F] = new KafkaContext[F](settings)
}
