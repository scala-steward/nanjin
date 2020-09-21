package com.github.chenharryhua.nanjin.spark.ftp

import akka.stream.IOResult
import akka.stream.alpakka.ftp.RemoteFileSettings
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift}
import com.github.chenharryhua.nanjin.devices.FtpUploader
import com.github.chenharryhua.nanjin.pipes._
import com.sksamuel.avro4s.{Encoder => AvroEncoder}
import fs2.Pipe
import io.circe.{Encoder => JsonEncoder}
import kantan.csv.{CsvConfiguration, RowEncoder}

final class FtpSink[F[_], C, S <: RemoteFileSettings](
  uploader: FtpUploader[F, C, S],
  blocker: Blocker) {

  def csv[A](pathStr: String, csvConfig: CsvConfiguration)(implicit
    enc: RowEncoder[A],
    cr: ConcurrentEffect[F],
    cs: ContextShift[F]): Pipe[F, A, IOResult] = {
    val pipe = new CsvSerialization[F, A](csvConfig)
    _.through(pipe.serialize(blocker)).through(uploader.upload(pathStr))
  }

  def csv[A](pathStr: String)(implicit
    enc: RowEncoder[A],
    cr: ConcurrentEffect[F],
    cs: ContextShift[F]): Pipe[F, A, IOResult] =
    csv[A](pathStr, CsvConfiguration.rfc)

  def json[A: JsonEncoder](pathStr: String)(implicit
    cr: ConcurrentEffect[F],
    cs: ContextShift[F]): Pipe[F, A, IOResult] = {
    val pipe = new CirceSerialization[F, A]
    _.through(pipe.serialize).through(uploader.upload(pathStr))
  }

  def jackson[A: AvroEncoder](pathStr: String)(implicit
    cs: ContextShift[F],
    ce: ConcurrentEffect[F]): Pipe[F, A, IOResult] = {
    val pipe = new JacksonSerialization[F](AvroEncoder[A].schema)
    val gr   = new GenericRecordEncoder[F, A]
    _.through(gr.encode).through(pipe.serialize).through(uploader.upload(pathStr))
  }

  def text(pathStr: String)(implicit
    cr: ConcurrentEffect[F],
    cs: ContextShift[F]): Pipe[F, String, IOResult] = {
    val pipe = new TextSerialization[F]
    _.through(pipe.serialize).through(uploader.upload(pathStr))
  }
}
