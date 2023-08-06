package com.github.chenharryhua.nanjin.pipes

import cats.effect.kernel.Async
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.chenharryhua.nanjin.terminals.NEWLINE_BYTES_SEPARATOR
import fs2.{Chunk, Pipe, Pull, Stream}
import fs2.io.toInputStream
import io.circe.Printer
import io.circe.jackson.jacksonToCirce
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory, JsonEncoder}

import java.io.{ByteArrayOutputStream, EOFException, InputStream}

object JacksonSerde {

  private def toJsonStr[F[_]](schema: Schema, isPretty: Boolean): Pipe[F, GenericRecord, String] = {
    val datumWriter = new GenericDatumWriter[GenericRecord](schema)
    val objMapper   = new ObjectMapper

    _.repeatPull(_.uncons1.flatMap {
      case None => Pull.pure(None)
      case Some((h, tl)) =>
        val baos: ByteArrayOutputStream = new ByteArrayOutputStream
        val encoder: JsonEncoder        = EncoderFactory.get().jsonEncoder(schema, baos)
        datumWriter.write(h, encoder)
        encoder.flush()
        baos.close()
        val json =
          if (isPretty)
            jacksonToCirce(objMapper.readTree(baos.toString)).printWith(Printer.spaces2)
          else
            baos.toString
        Pull.output1(json).as(Some(tl))
    })
  }

  def prettyJson[F[_]](schema: Schema): Pipe[F, GenericRecord, String] = toJsonStr[F](schema, isPretty = true)
  def compactJson[F[_]](schema: Schema): Pipe[F, GenericRecord, String] =
    toJsonStr[F](schema, isPretty = false)

  def toBytes[F[_]](schema: Schema): Pipe[F, GenericRecord, Byte] = {
    val datumWriter = new GenericDatumWriter[GenericRecord](schema)
    (sfgr: Stream[F, GenericRecord]) =>
      sfgr.chunks.map { grs =>
        val baos: ByteArrayOutputStream = new ByteArrayOutputStream
        val encoder: JsonEncoder        = EncoderFactory.get().jsonEncoder(schema, baos)
        grs.foreach(gr => datumWriter.write(gr, encoder))
        encoder.flush()
        baos.close()
        baos.toByteArray
      }.intersperse(NEWLINE_BYTES_SEPARATOR).flatMap(ba => Stream.chunk(Chunk.vector(ba.toVector)))
  }

  def fromBytes[F[_]](schema: Schema)(implicit F: Async[F]): Pipe[F, Byte, GenericRecord] = {
    (ss: Stream[F, Byte]) =>
      ss.through(toInputStream).flatMap { is =>
        val jsonDecoder = DecoderFactory.get().jsonDecoder(schema, is)
        val datumReader = new GenericDatumReader[GenericRecord](schema)
        def pullAll(is: InputStream): Pull[F, GenericRecord, Option[InputStream]] =
          Pull
            .functionKInstance(F.blocking(try Some(datumReader.read(null, jsonDecoder))
            catch { case _: EOFException => None }))
            .flatMap {
              case Some(a) => Pull.output1(a) >> Pull.pure[F, Option[InputStream]](Some(is))
              case None    => Pull.eval(F.blocking(is.close())) >> Pull.pure(None)
            }
        Pull.loop(pullAll)(is).void.stream
      }
  }
}
