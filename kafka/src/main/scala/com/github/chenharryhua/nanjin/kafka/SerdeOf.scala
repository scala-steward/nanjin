package com.github.chenharryhua.nanjin.kafka

import java.{util => ju}

import com.sksamuel.avro4s.{AvroSchema, SchemaFor}
import org.apache.avro.Schema
import org.apache.kafka.common.serialization.{Deserializer, Serde, Serializer}
import org.apache.kafka.streams.scala.Serdes

import scala.collection.JavaConverters._

sealed abstract class KafkaSerde[A](
  schema: Schema,
  serializer: Serializer[A],
  deserializer: Deserializer[A],
  props: Map[String, String])
    extends Serde[A] {
  override def configure(configs: ju.Map[String, _], isKey: Boolean): Unit = {
    serializer.configure(configs, isKey)
    deserializer.configure(configs, isKey)
  }
  override def close(): Unit = {
    serializer.close()
    deserializer.close()
  }
}

final case class KeySerde[A](
  schema: Schema,
  serializer: Serializer[A],
  deserializer: Deserializer[A],
  props: Map[String, String])
    extends KafkaSerde[A](schema, serializer, deserializer, props) {
  configure(props.asJava, isKey = true)
}

final case class ValueSerde[A](
  schema: Schema,
  serializer: Serializer[A],
  deserializer: Deserializer[A],
  props: Map[String, String])
    extends KafkaSerde[A](schema, serializer, deserializer, props) {
  configure(props.asJava, isKey = false)
}

abstract class SerdeModule(srSettings: SchemaRegistrySettings) {

  sealed abstract class SerdeOf[A](val schema: Schema) extends Serializable {
    def serializer: Serializer[A]

    def deserializer: Deserializer[A]

    final def asKey(props: Map[String, String]): KeySerde[A] =
      KeySerde(schema, serializer, deserializer, srSettings.props ++ props)

    final def asValue(props: Map[String, String]): ValueSerde[A] =
      ValueSerde(schema, serializer, deserializer, srSettings.props ++ props)
  }

  sealed protected trait Priority0 {

    import com.sksamuel.avro4s.{Decoder, Encoder}

    implicit def kavroSerde[A: Encoder: Decoder: SchemaFor]: SerdeOf[A] = {
      val serde: Serde[A] = new KafkaAvroSerde[A](srSettings)
      new SerdeOf[A](AvroSchema[A]) {
        override val deserializer: Deserializer[A] = serde.deserializer
        override val serializer: Serializer[A]     = serde.serializer
      }
    }
  }

  sealed protected trait Priority1 extends Priority0 {

    import io.circe.{Decoder, Encoder}

    implicit def kjsonSerde[A: Decoder: Encoder]: SerdeOf[KJson[A]] = {
      val serde: Serde[KJson[A]] = new KafkaJsonSerde[A]
      new SerdeOf[KJson[A]](SchemaFor[String].schema) {
        override val deserializer: Deserializer[KJson[A]] = serde.deserializer()
        override val serializer: Serializer[KJson[A]]     = serde.serializer()
      }
    }
  }

  object SerdeOf extends Priority1 {
    def apply[A](implicit ev: SerdeOf[A]): SerdeOf[A] = ev

    implicit object kstringSerde extends SerdeOf[String](SchemaFor[String].schema) {
      override val deserializer: Deserializer[String] = Serdes.String.deserializer()
      override val serializer: Serializer[String]     = Serdes.String.serializer()
    }

    implicit object kintSerde extends SerdeOf[Int](SchemaFor[Int].schema) {
      override val deserializer: Deserializer[Int] = Serdes.Integer.deserializer()
      override val serializer: Serializer[Int]     = Serdes.Integer.serializer()
    }

    implicit object klongSerde extends SerdeOf[Long](SchemaFor[Long].schema) {
      override val deserializer: Deserializer[Long] = Serdes.Long.deserializer()
      override val serializer: Serializer[Long]     = Serdes.Long.serializer()
    }

    implicit object kdoubleSerde extends SerdeOf[Double](SchemaFor[Double].schema) {
      override val deserializer: Deserializer[Double] = Serdes.Double.deserializer()
      override val serializer: Serializer[Double]     = Serdes.Double.serializer()
    }

    implicit object kbyteArraySerde extends SerdeOf[Array[Byte]](SchemaFor[Array[Byte]].schema) {
      override val deserializer: Deserializer[Array[Byte]] = Serdes.ByteArray.deserializer()
      override val serializer: Serializer[Array[Byte]]     = Serdes.ByteArray.serializer()
    }
  }
}
