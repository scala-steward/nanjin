package com.github.chenharryhua.nanjin.spark.persist

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Sync}
import com.github.chenharryhua.nanjin.devices.NJHadoop
import com.github.chenharryhua.nanjin.pipes._
import com.sksamuel.avro4s.{Decoder => AvroDecoder}
import frameless.TypedEncoder
import fs2.Stream
import io.circe.{Decoder => JsonDecoder}
import kantan.csv.{CsvConfiguration, RowDecoder}
import org.apache.hadoop.conf.Configuration
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

final class SingleFileSource[F[_]](blocker: Blocker, conf: Configuration) {

// 1
  def jackson[A](pathStr: String)(implicit
    dec: AvroDecoder[A],
    cs: ContextShift[F],
    ce: ConcurrentEffect[F]): Stream[F, A] = {
    val pipe = new JacksonDeserialization[F](dec.schema)
    val gr   = new GenericRecordDecoder[F, A]
    new NJHadoop[F](conf, blocker).byteStream(pathStr).through(pipe.deserialize).through(gr.decode)
  }

// 2
  def circe[A: JsonDecoder](
    pathStr: String)(implicit F: Sync[F], cs: ContextShift[F]): Stream[F, A] = {
    val pipe = new CirceDeserialization[F, A]
    new NJHadoop[F](conf, blocker).byteStream(pathStr).through(pipe.deserialize)
  }

// 3
  def text(
    pathStr: String)(implicit cs: ContextShift[F], ce: ConcurrentEffect[F]): Stream[F, String] = {
    val pipe = new TextDeserialization[F]
    new NJHadoop[F](conf, blocker).byteStream(pathStr).through(pipe.deserialize)
  }

// 4
  def csv[A](pathStr: String, csvConfig: CsvConfiguration)(implicit
    dec: RowDecoder[A],
    F: ConcurrentEffect[F],
    cs: ContextShift[F]): Stream[F, A] = {
    val pipe = new CsvDeserialization[F, A](csvConfig)
    new NJHadoop[F](conf, blocker).byteStream(pathStr).through(pipe.deserialize)
  }

  def csv[A](pathStr: String)(implicit
    dec: RowDecoder[A],
    F: ConcurrentEffect[F],
    cs: ContextShift[F]): Stream[F, A] =
    csv[A](pathStr, CsvConfiguration.rfc)

// 11
  def parquet[A: TypedEncoder](pathStr: String)(implicit
    dec: AvroDecoder[A],
    F: Sync[F],
    cs: ContextShift[F]): Stream[F, A] = {
    val pipe = new GenericRecordDecoder[F, A]
    new NJHadoop[F](conf, blocker).parquetSource(pathStr, dec.schema).through(pipe.decode)
  }

// 12
  def avro[A](pathStr: String)(implicit
    dec: AvroDecoder[A],
    cs: ContextShift[F],
    ce: ConcurrentEffect[F]): Stream[F, A] = {
    val pipe = new GenericRecordDecoder[F, A]
    new NJHadoop[F](conf, blocker).avroSource(pathStr, dec.schema).through(pipe.decode)
  }

// 13
  def binAvro[A](pathStr: String)(implicit
    dec: AvroDecoder[A],
    cs: ContextShift[F],
    ce: ConcurrentEffect[F]): Stream[F, A] = {
    val pipe = new BinaryAvroDeserialization[F](dec.schema)
    val gr   = new GenericRecordDecoder[F, A]
    new NJHadoop[F](conf, blocker).byteStream(pathStr).through(pipe.deserialize).through(gr.decode)
  }

// 14
  def javaObject[A](
    pathStr: String)(implicit cs: ContextShift[F], ce: ConcurrentEffect[F]): Stream[F, A] = {
    val pipe = new JavaObjectDeserialization[F, A]
    new NJHadoop[F](conf, blocker).byteStream(pathStr).through(pipe.deserialize)

  }

// 15
  def protobuf[A <: GeneratedMessage](pathStr: String)(implicit
    cs: ContextShift[F],
    ce: ConcurrentEffect[F],
    ev: GeneratedMessageCompanion[A]): Stream[F, A] = {
    val pipe = new DelimitedProtoBufDeserialization[F, A]()
    new NJHadoop[F](conf, blocker).byteStream(pathStr).through(pipe.deserialize)
  }
}
