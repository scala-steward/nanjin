package com.github.chenharryhua.nanjin.terminals

import cats.data.Reader
import cats.effect.kernel.{Resource, Sync}
import cats.implicits.catsSyntaxFlatMapOps
import fs2.Chunk
import kantan.csv.CsvConfiguration
import kantan.csv.CsvConfiguration.Header
import kantan.csv.engine.WriterEngine
import org.apache.avro.Schema
import org.apache.avro.file.{CodecFactory, DataFileWriter}
import org.apache.avro.generic.{GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{Encoder, EncoderFactory}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.io.compress.zlib.ZlibCompressor.CompressionLevel
import org.apache.hadoop.io.compress.zlib.ZlibFactory
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.util.HadoopOutputFile

import java.io.{OutputStream, PrintWriter}
import java.nio.charset.{Charset, StandardCharsets}

sealed private trait HadoopWriter[F[_], A] {
  def write(ck: Chunk[A]): F[Unit]
}

private object HadoopWriter {

  def avroR[F[_]](
    codecFactory: CodecFactory,
    schema: Schema,
    configuration: Configuration,
    blockSizeHint: Long,
    path: Path)(implicit F: Sync[F]): Resource[F, HadoopWriter[F, GenericRecord]] =
    for {
      dfw <- Resource.make[F, DataFileWriter[GenericRecord]](
        F.blocking(new DataFileWriter(new GenericDatumWriter(schema)).setCodec(codecFactory)))(r =>
        F.blocking(r.close()))
      os <-
        Resource.make(
          F.blocking(HadoopOutputFile.fromPath(path, configuration).createOrOverwrite(blockSizeHint)))(r =>
          F.blocking(r.flush()) >> F.blocking(r.close()))
      writer <- Resource.make(F.blocking(dfw.create(schema, os)))(r => F.blocking(r.close()))
    } yield new HadoopWriter[F, GenericRecord] {
      override def write(ck: Chunk[GenericRecord]): F[Unit] =
        F.blocking(ck.foreach(writer.append)) // don't flush
    }

  def parquetR[F[_]](writeBuilder: Reader[Path, AvroParquetWriter.Builder[GenericRecord]], path: Path)(
    implicit F: Sync[F]): Resource[F, HadoopWriter[F, GenericRecord]] =
    Resource
      .make(F.blocking(writeBuilder.run(path).build()))(r => F.blocking(r.close()))
      .map(pw =>
        new HadoopWriter[F, GenericRecord] {
          override def write(ck: Chunk[GenericRecord]): F[Unit] =
            F.blocking(ck.foreach(pw.write))
        })

  private def fileOutputStream(
    path: Path,
    configuration: Configuration,
    compressionLevel: CompressionLevel,
    blockSizeHint: Long): OutputStream = {
    ZlibFactory.setCompressionLevel(configuration, compressionLevel)
    val os: OutputStream = HadoopOutputFile.fromPath(path, configuration).createOrOverwrite(blockSizeHint)
    Option(new CompressionCodecFactory(configuration).getCodec(path)) match {
      case Some(cc) => cc.createOutputStream(os)
      case None     => os
    }
  }

  def kantanR[F[_]](
    configuration: Configuration,
    compressionLevel: CompressionLevel,
    blockSizeHint: Long,
    csvConfiguration: CsvConfiguration,
    path: Path)(implicit F: Sync[F], engine: WriterEngine): Resource[F, HadoopWriter[F, Seq[String]]] =
    Resource
      .make(F.blocking {
        val pw = new PrintWriter(
          fileOutputStream(path, configuration, compressionLevel, blockSizeHint),
          true,
          StandardCharsets.UTF_8)
        val csvWriter = engine.writerFor(pw, csvConfiguration)
        csvConfiguration.header match {
          case Header.None             => ()
          case Header.Implicit         => csvWriter.write(List("no explicitly provided header")): Unit
          case Header.Explicit(header) => csvWriter.write(header): Unit
        }
        csvWriter
      })(r => F.blocking(r.close()))
      .map { cw =>
        new HadoopWriter[F, Seq[String]] {
          override def write(ck: Chunk[Seq[String]]): F[Unit] =
            F.blocking(ck.foreach(cw.write(_): Unit))
        }
      }

  private def fileOutputStreamR[F[_]](
    path: Path,
    configuration: Configuration,
    compressionLevel: CompressionLevel,
    blockSizeHint: Long)(implicit F: Sync[F]): Resource[F, OutputStream] =
    Resource.make(F.blocking(fileOutputStream(path, configuration, compressionLevel, blockSizeHint)))(r =>
      F.blocking(r.close()))

  def byteR[F[_]](
    configuration: Configuration,
    compressionLevel: CompressionLevel,
    blockSizeHint: Long,
    path: Path)(implicit F: Sync[F]): Resource[F, HadoopWriter[F, Byte]] =
    fileOutputStreamR(path, configuration, compressionLevel, blockSizeHint).map(os =>
      new HadoopWriter[F, Byte] {
        override def write(ck: Chunk[Byte]): F[Unit] =
          F.blocking(os.write(ck.toArray)) >> F.blocking(os.flush())
      })

  def stringR[F[_]](
    configuration: Configuration,
    compressionLevel: CompressionLevel,
    blockSizeHint: Long,
    charset: Charset,
    path: Path)(implicit F: Sync[F]): Resource[F, HadoopWriter[F, String]] =
    Resource
      .make(
        F.blocking(
          new PrintWriter(
            fileOutputStream(path, configuration, compressionLevel, blockSizeHint),
            true,
            charset)))(r => F.blocking(r.close()))
      .map(os =>
        new HadoopWriter[F, String] {
          override def write(ck: Chunk[String]): F[Unit] =
            F.blocking(ck.foreach(os.write))
        })

  private def genericRecordWriter[F[_]](
    getEncoder: OutputStream => Encoder,
    configuration: Configuration,
    compressionLevel: CompressionLevel,
    blockSizeHint: Long,
    schema: Schema,
    path: Path)(implicit F: Sync[F]): Resource[F, HadoopWriter[F, GenericRecord]] =
    fileOutputStreamR(path, configuration, compressionLevel, blockSizeHint).map { os =>
      val encoder: Encoder = getEncoder(os)
      val datumWriter      = new GenericDatumWriter[GenericRecord](schema)
      new HadoopWriter[F, GenericRecord] {
        override def write(ck: Chunk[GenericRecord]): F[Unit] =
          F.blocking(ck.foreach(gr => datumWriter.write(gr, encoder))) >> F.blocking(encoder.flush())
      }
    }

  def jacksonR[F[_]](
    configuration: Configuration,
    compressionLevel: CompressionLevel,
    blockSizeHint: Long,
    schema: Schema,
    path: Path)(implicit F: Sync[F]): Resource[F, HadoopWriter[F, GenericRecord]] =
    genericRecordWriter[F](
      (os: OutputStream) => EncoderFactory.get().jsonEncoder(schema, os),
      configuration,
      compressionLevel,
      blockSizeHint,
      schema,
      path)

  def binAvroR[F[_]](
    configuration: Configuration,
    compressionLevel: CompressionLevel,
    blockSizeHint: Long,
    schema: Schema,
    path: Path)(implicit F: Sync[F]): Resource[F, HadoopWriter[F, GenericRecord]] =
    genericRecordWriter[F](
      (os: OutputStream) => EncoderFactory.get().binaryEncoder(os, null),
      configuration,
      compressionLevel,
      blockSizeHint,
      schema,
      path)
}
