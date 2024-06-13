package com.github.chenharryhua.nanjin.terminals

import cats.data.Reader
import cats.effect.kernel.{Resource, Sync}
import com.fasterxml.jackson.core.util.MinimalPrettyPrinter
import com.fasterxml.jackson.core.{JsonFactory, JsonGenerator}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import fs2.Chunk
import org.apache.avro.Schema
import org.apache.avro.file.{CodecFactory, DataFileWriter}
import org.apache.avro.generic.{GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{Encoder, EncoderFactory}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.util.HadoopOutputFile

import java.io.{OutputStream, PrintWriter}
import java.nio.charset.StandardCharsets

sealed private trait HadoopWriter[F[_], A] {
  def write(ck: Chunk[A]): F[Unit]
}

private object HadoopWriter {
  final private val BLOCK_SIZE_HINT: Long = -1

  def avroR[F[_]](codecFactory: CodecFactory, schema: Schema, configuration: Configuration, path: Path)(
    implicit F: Sync[F]): Resource[F, HadoopWriter[F, GenericRecord]] =
    for {
      dfw <- Resource.make[F, DataFileWriter[GenericRecord]](
        F.blocking(new DataFileWriter(new GenericDatumWriter(schema)).setCodec(codecFactory)))(r =>
        F.blocking(r.close()))
      os <-
        Resource.make(
          F.blocking(HadoopOutputFile.fromPath(path, configuration).createOrOverwrite(BLOCK_SIZE_HINT)))(r =>
          F.blocking(r.close()))
      writer <- Resource.make(F.blocking(dfw.create(schema, os)))(r => F.blocking(r.close()))
    } yield new HadoopWriter[F, GenericRecord] {
      override def write(cgr: Chunk[GenericRecord]): F[Unit] =
        F.blocking {
          cgr.foreach(writer.append)
          writer.flush()
        }
    }

  def parquetR[F[_]](writeBuilder: Reader[Path, AvroParquetWriter.Builder[GenericRecord]], path: Path)(
    implicit F: Sync[F]): Resource[F, HadoopWriter[F, GenericRecord]] =
    Resource
      .make(F.blocking(writeBuilder.run(path).build()))(r => F.blocking(r.close()))
      .map(pw =>
        new HadoopWriter[F, GenericRecord] {
          override def write(cgr: Chunk[GenericRecord]): F[Unit] =
            F.blocking(cgr.foreach(pw.write))
        })

  private def fileOutputStream(path: Path, configuration: Configuration): OutputStream = {
    val os: OutputStream = HadoopOutputFile.fromPath(path, configuration).createOrOverwrite(BLOCK_SIZE_HINT)
    Option(new CompressionCodecFactory(configuration).getCodec(path)) match {
      case Some(cc) => cc.createOutputStream(os)
      case None     => os
    }
  }

  def outputStreamR[F[_]](path: Path, configuration: Configuration)(implicit
    F: Sync[F]): Resource[F, OutputStream] =
    Resource.make(F.blocking(fileOutputStream(path, configuration)))(r => F.blocking(r.close()))

  def byteR[F[_]](configuration: Configuration, path: Path)(implicit
    F: Sync[F]): Resource[F, HadoopWriter[F, Byte]] =
    outputStreamR(path, configuration).map(os =>
      new HadoopWriter[F, Byte] {
        override def write(cb: Chunk[Byte]): F[Unit] =
          F.blocking {
            os.write(cb.toArray)
            os.flush()
          }
      })

  def stringR[F[_]](configuration: Configuration, path: Path)(implicit
    F: Sync[F]): Resource[F, HadoopWriter[F, String]] =
    Resource
      .make(
        F.blocking(new PrintWriter(fileOutputStream(path, configuration), false, StandardCharsets.UTF_8)))(
        r => F.blocking(r.close()))
      .map(pw =>
        new HadoopWriter[F, String] {
          override def write(cs: Chunk[String]): F[Unit] =
            F.blocking {
              cs.foreach(pw.println)
              pw.flush()
            }
        })

  def csvStringR[F[_]](configuration: Configuration, path: Path)(implicit
    F: Sync[F]): Resource[F, HadoopWriter[F, String]] =
    Resource
      .make(
        F.blocking(new PrintWriter(fileOutputStream(path, configuration), false, StandardCharsets.UTF_8)))(
        r => F.blocking(r.close()))
      .map(pw =>
        new HadoopWriter[F, String] {
          override def write(cs: Chunk[String]): F[Unit] =
            F.blocking {
              cs.foreach(pw.write) // already has new line separator
              pw.flush()
            }
        })

  def jsonNodeR[F[_]](configuration: Configuration, path: Path, mapper: ObjectMapper)(implicit
    F: Sync[F]): Resource[F, HadoopWriter[F, JsonNode]] =
    outputStreamR(path, configuration).map { os =>
      val mpp: MinimalPrettyPrinter = new MinimalPrettyPrinter()
      mpp.setRootValueSeparator(System.lineSeparator())

      val generator: JsonGenerator =
        new JsonFactory(mapper).createGenerator(os).setPrettyPrinter(mpp)

      new HadoopWriter[F, JsonNode] {
        override def write(cjn: Chunk[JsonNode]): F[Unit] =
          F.blocking {
            cjn.foreach(generator.writeTree)
            os.flush()
          }
      }
    }

  private def genericRecordWriterR[F[_]](
    getEncoder: OutputStream => Encoder,
    configuration: Configuration,
    schema: Schema,
    path: Path)(implicit F: Sync[F]): Resource[F, HadoopWriter[F, GenericRecord]] =
    outputStreamR(path, configuration).map { os =>
      val encoder: Encoder = getEncoder(os)
      val datumWriter      = new GenericDatumWriter[GenericRecord](schema)
      new HadoopWriter[F, GenericRecord] {
        override def write(cgr: Chunk[GenericRecord]): F[Unit] =
          F.blocking {
            cgr.foreach(gr => datumWriter.write(gr, encoder))
            encoder.flush()
          }
      }
    }

  def jacksonR[F[_]](configuration: Configuration, schema: Schema, path: Path)(implicit
    F: Sync[F]): Resource[F, HadoopWriter[F, GenericRecord]] =
    genericRecordWriterR[F](
      (os: OutputStream) => EncoderFactory.get().jsonEncoder(schema, os),
      configuration,
      schema,
      path)

  def binAvroR[F[_]](configuration: Configuration, schema: Schema, path: Path)(implicit
    F: Sync[F]): Resource[F, HadoopWriter[F, GenericRecord]] =
    genericRecordWriterR[F](
      (os: OutputStream) => EncoderFactory.get().binaryEncoder(os, null),
      configuration,
      schema,
      path)
}
