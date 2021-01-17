package com.github.chenharryhua.nanjin.spark.kafka

import cats.Bifunctor
import cats.implicits.catsSyntaxTuple2Semigroupal
import cats.kernel.PartialOrder
import cats.syntax.all._
import com.github.chenharryhua.nanjin.datetime.NJTimestamp
import com.github.chenharryhua.nanjin.kafka.TopicDef
import com.github.chenharryhua.nanjin.messages.kafka._
import com.github.chenharryhua.nanjin.messages.kafka.codec.AvroCodec
import com.github.chenharryhua.nanjin.spark.AvroTypedEncoder
import com.sksamuel.avro4s._
import frameless.TypedEncoder
import fs2.kafka.{ConsumerRecord => Fs2ConsumerRecord}
import io.circe.generic.auto._
import io.circe.{Json, Encoder => JsonEncoder}
import monocle.macros.Lenses
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.record.TimestampType
import shapeless.cachedImplicit

import scala.util.Try

@Lenses
@AvroDoc("kafka record, optional Key and Value")
@AvroNamespace("nj.spark.kafka")
@AvroName("NJConsumerRecord")
final case class NJConsumerRecord[K, V](
  @AvroDoc("kafka partition") partition: Int,
  @AvroDoc("kafka offset") offset: Long,
  @AvroDoc("kafka timestamp in millisecond") timestamp: Long,
  @AvroDoc("kafka key") key: Option[K],
  @AvroDoc("kafka value") value: Option[V],
  @AvroDoc("kafka topic") topic: String,
  @AvroDoc("kafka timestamp type") timestampType: Int) {

  def newKey[K2](key: Option[K2]): NJConsumerRecord[K2, V]     = copy(key = key)
  def newValue[V2](value: Option[V2]): NJConsumerRecord[K, V2] = copy(value = value)

  def flatten[K2, V2](implicit
    evK: K <:< Option[K2],
    evV: V <:< Option[V2]
  ): NJConsumerRecord[K2, V2] =
    copy(key = key.flatten, value = value.flatten)

  def toNJProducerRecord: NJProducerRecord[K, V] =
    NJProducerRecord[K, V](Some(partition), Some(offset), Some(timestamp), key, value)

  def asJson(implicit k: JsonEncoder[K], v: JsonEncoder[V]): Json =
    JsonEncoder[NJConsumerRecord[K, V]].apply(this)

  private def tst: TimestampType = timestampType match {
    case 0  => TimestampType.CREATE_TIME
    case 1  => TimestampType.LOG_APPEND_TIME
    case -1 => TimestampType.NO_TIMESTAMP_TYPE
    case _  => sys.error("timestamp type should be -1, 0 or 1")
  }

  def metaInfo: String =
    s"Meta(topic=$topic,partition=$partition,offset=$offset,ts=${NJTimestamp(timestamp).utc},tt=${tst.toString})"

  override def toString: String =
    s"CR($metaInfo,key=${key.toString},value=${value.toString})"
}

object NJConsumerRecord {

  def apply[K, V](cr: ConsumerRecord[Option[K], Option[V]]): NJConsumerRecord[K, V] =
    NJConsumerRecord(cr.partition, cr.offset, cr.timestamp, cr.key, cr.value, cr.topic, cr.timestampType.id)

  def apply[K, V](cr: Fs2ConsumerRecord[Try[K], Try[V]]): NJConsumerRecord[K, V] =
    apply(toConsumerRecord.transform(cr).bimap(_.toOption, _.toOption))

  def avroCodec[K, V](keyCodec: AvroCodec[K], valCodec: AvroCodec[V]): AvroCodec[NJConsumerRecord[K, V]] = {
    implicit val schemaForKey: SchemaFor[K]  = keyCodec.schemaFor
    implicit val schemaForVal: SchemaFor[V]  = valCodec.schemaFor
    implicit val keyDecoder: Decoder[K]      = keyCodec.avroDecoder
    implicit val valDecoder: Decoder[V]      = valCodec.avroDecoder
    implicit val keyEncoder: Encoder[K]      = keyCodec.avroEncoder
    implicit val valEncoder: Encoder[V]      = valCodec.avroEncoder
    val s: SchemaFor[NJConsumerRecord[K, V]] = cachedImplicit
    val d: Decoder[NJConsumerRecord[K, V]]   = cachedImplicit
    val e: Encoder[NJConsumerRecord[K, V]]   = cachedImplicit
    AvroCodec[NJConsumerRecord[K, V]](s, d.withSchema(s), e.withSchema(s))
  }

  def avroCodec[K, V](topicDef: TopicDef[K, V]): AvroCodec[NJConsumerRecord[K, V]] =
    avroCodec(topicDef.serdeOfKey.avroCodec, topicDef.serdeOfVal.avroCodec)

  def ate[K, V](keyCodec: AvroCodec[K], valCodec: AvroCodec[V])(implicit
    tek: TypedEncoder[K],
    tev: TypedEncoder[V]): AvroTypedEncoder[NJConsumerRecord[K, V]] = {
    val ote: TypedEncoder[NJConsumerRecord[K, V]] = shapeless.cachedImplicit
    AvroTypedEncoder[NJConsumerRecord[K, V]](ote, avroCodec(keyCodec, valCodec))
  }

  def ate[K, V](topicDef: TopicDef[K, V])(implicit
    tek: TypedEncoder[K],
    tev: TypedEncoder[V]): AvroTypedEncoder[NJConsumerRecord[K, V]] =
    ate(topicDef.serdeOfKey.avroCodec, topicDef.serdeOfVal.avroCodec)

  implicit val bifunctorOptionalKV: Bifunctor[NJConsumerRecord] =
    new Bifunctor[NJConsumerRecord] {

      override def bimap[A, B, C, D](fab: NJConsumerRecord[A, B])(f: A => C, g: B => D): NJConsumerRecord[C, D] =
        fab.copy(key = fab.key.map(f), value = fab.value.map(g))
    }

  implicit def partialOrderOptionlKV[K, V]: PartialOrder[NJConsumerRecord[K, V]] =
    (x: NJConsumerRecord[K, V], y: NJConsumerRecord[K, V]) =>
      if (x.partition === y.partition) {
        if (x.offset < y.offset) -1.0 else if (x.offset > y.offset) 1.0 else 0.0
      } else Double.NaN
}
