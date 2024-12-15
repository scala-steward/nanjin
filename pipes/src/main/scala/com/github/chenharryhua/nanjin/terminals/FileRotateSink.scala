package com.github.chenharryhua.nanjin.terminals

import cats.Endo
import cats.data.Reader
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Hotswap
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.github.chenharryhua.nanjin.common.chrono.TickedValue
import fs2.{Chunk, Pipe, Pull, Stream}
import io.circe.Json
import io.circe.jackson.circeToJackson
import io.lemonlabs.uri.Url
import kantan.csv.CsvConfiguration
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.avro.AvroParquetWriter.Builder
import org.apache.parquet.hadoop.ParquetFileWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.HadoopOutputFile
import scalapb.GeneratedMessage

sealed private trait MergeOrZip
private object MergeOrZip {
  case object Merge extends MergeOrZip
  case object Zip extends MergeOrZip
}

final class FileRotateSink[F[_]: Async] private (
  mergeOrZip: MergeOrZip,
  configuration: Configuration,
  paths: Stream[F, TickedValue[Path]]) {
  // avro
  def avro(compression: AvroCompression): Pipe[F, Chunk[GenericRecord], TickedValue[Int]] = {
    def get_writer(schema: Schema)(url: Path): Resource[F, HadoopWriter[F, GenericRecord]] =
      HadoopWriter.avroR[F](compression.codecFactory, schema, configuration, url)

    mergeOrZip match {
      case MergeOrZip.Merge =>
        (ss: Stream[F, Chunk[GenericRecord]]) =>
          ss.pull.peek1.flatMap {
            case Some((grs, stream)) =>
              periodically.merge.persist(stream, paths, get_writer(grs(0).getSchema))
            case None => Pull.done
          }.stream
      case MergeOrZip.Zip =>
        (ss: Stream[F, Chunk[GenericRecord]]) =>
          ss.pull.peek1.flatMap {
            case Some((grs, stream)) =>
              periodically.zip.persist(get_writer(grs(0).getSchema), stream.zip(paths))
            case None => Pull.done
          }.stream
    }
  }

  def avro(f: AvroCompression.type => AvroCompression): Pipe[F, Chunk[GenericRecord], TickedValue[Int]] =
    avro(f(AvroCompression))

  val avro: Pipe[F, Chunk[GenericRecord], TickedValue[Int]] =
    avro(AvroCompression.Uncompressed)

  // binary avro
  val binAvro: Pipe[F, Chunk[GenericRecord], TickedValue[Int]] = {
    def get_writer(schema: Schema)(url: Path): Resource[F, HadoopWriter[F, GenericRecord]] =
      HadoopWriter.binAvroR[F](configuration, schema, url)

    mergeOrZip match {
      case MergeOrZip.Merge =>
        (ss: Stream[F, Chunk[GenericRecord]]) =>
          ss.pull.peek1.flatMap {
            case Some((grs, stream)) =>
              periodically.merge.persist(stream, paths, get_writer(grs(0).getSchema))
            case None => Pull.done
          }.stream
      case MergeOrZip.Zip =>
        (ss: Stream[F, Chunk[GenericRecord]]) =>
          ss.pull.peek1.flatMap {
            case Some((grs, stream)) =>
              periodically.zip.persist(get_writer(grs(0).getSchema), stream.zip(paths))
            case None => Pull.done
          }.stream
    }
  }

  // jackson json
  val jackson: Pipe[F, Chunk[GenericRecord], TickedValue[Int]] = {
    def get_writer(schema: Schema)(url: Path): Resource[F, HadoopWriter[F, GenericRecord]] =
      HadoopWriter.jacksonR[F](configuration, schema, url)

    mergeOrZip match {
      case MergeOrZip.Merge =>
        (ss: Stream[F, Chunk[GenericRecord]]) =>
          ss.pull.peek1.flatMap {
            case Some((grs, stream)) =>
              periodically.merge.persist(stream, paths, get_writer(grs(0).getSchema))
            case None => Pull.done
          }.stream
      case MergeOrZip.Zip =>
        (ss: Stream[F, Chunk[GenericRecord]]) =>
          ss.pull.peek1.flatMap {
            case Some((grs, stream)) =>
              periodically.zip.persist(get_writer(grs(0).getSchema), stream.zip(paths))
            case None => Pull.done
          }.stream
    }
  }

  // parquet
  def parquet(f: Endo[Builder[GenericRecord]]): Pipe[F, Chunk[GenericRecord], TickedValue[Int]] = {

    def get_writer(schema: Schema)(url: Path): Resource[F, HadoopWriter[F, GenericRecord]] = {
      val writeBuilder: Reader[Path, Builder[GenericRecord]] = Reader((path: Path) =>
        AvroParquetWriter
          .builder[GenericRecord](HadoopOutputFile.fromPath(path, configuration))
          .withDataModel(GenericData.get())
          .withConf(configuration)
          .withSchema(schema)
          .withCompressionCodec(CompressionCodecName.UNCOMPRESSED)
          .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)).map(f)

      HadoopWriter.parquetR[F](writeBuilder, url)
    }

    mergeOrZip match {
      case MergeOrZip.Merge =>
        (ss: Stream[F, Chunk[GenericRecord]]) =>
          ss.pull.peek1.flatMap {
            case Some((grs, stream)) =>
              periodically.merge.persist(stream, paths, get_writer(grs(0).getSchema))
            case None => Pull.done
          }.stream
      case MergeOrZip.Zip =>
        (ss: Stream[F, Chunk[GenericRecord]]) =>
          ss.pull.peek1.flatMap {
            case Some((grs, stream)) =>
              periodically.zip.persist(get_writer(grs(0).getSchema), stream.zip(paths))
            case None => Pull.done
          }.stream
    }
  }

  val parquet: Pipe[F, Chunk[GenericRecord], TickedValue[Int]] =
    parquet(identity)

  // bytes
  val bytes: Pipe[F, Chunk[Byte], TickedValue[Int]] = {
    def get_writer(url: Path): Resource[F, HadoopWriter[F, Byte]] =
      HadoopWriter.byteR[F](configuration, url)

    mergeOrZip match {
      case MergeOrZip.Merge =>
        (ss: Stream[F, Chunk[Byte]]) => periodically.merge.persist(ss, paths, get_writer).stream
      case MergeOrZip.Zip =>
        (ss: Stream[F, Chunk[Byte]]) => periodically.zip.persist(get_writer, ss.zip(paths)).stream
    }
  }

  // circe json
  val circe: Pipe[F, Chunk[Json], TickedValue[Int]] = {
    val mapper: ObjectMapper = new ObjectMapper() // create ObjectMapper is expensive

    def get_writer(url: Path): Resource[F, HadoopWriter[F, JsonNode]] =
      HadoopWriter.jsonNodeR[F](configuration, url, mapper)

    mergeOrZip match {
      case MergeOrZip.Merge =>
        (ss: Stream[F, Chunk[Json]]) =>
          periodically.merge.persist(ss.map(_.map(circeToJackson)), paths, get_writer).stream
      case MergeOrZip.Zip =>
        (ss: Stream[F, Chunk[Json]]) =>
          periodically.zip.persist(get_writer, ss.map(_.map(circeToJackson)).zip(paths)).stream
    }
  }

  // kantan csv
  def kantan(csvConfiguration: CsvConfiguration): Pipe[F, Chunk[Seq[String]], TickedValue[Int]] = {
    def get_writer(url: Path): Resource[F, HadoopWriter[F, String]] =
      HadoopWriter.csvStringR[F](configuration, url)

    mergeOrZip match {
      case MergeOrZip.Merge =>
        (ss: Stream[F, Chunk[Seq[String]]]) =>
          val encodedSrc: Stream[F, Chunk[String]] = ss.map(_.map(csvRow(csvConfiguration)))
          if (csvConfiguration.hasHeader) {
            val header: Chunk[String] = csvHeader(csvConfiguration)
            paths.pull.uncons1.flatMap {
              case Some((head, tail)) =>
                Stream
                  .resource(Hotswap(get_writer(head.value)))
                  .flatMap { case (hotswap, writer) =>
                    Stream.eval(writer.write(header)) >>
                      periodically
                        .persistCsvWithHeader[F, Path](
                          head.tick,
                          get_writer,
                          hotswap,
                          writer,
                          encodedSrc.map(Left(_)).mergeHaltBoth(tail.map(Right(_))),
                          header
                        )
                        .stream
                  }
                  .pull
                  .echo
              case None => Pull.done
            }.stream
          } else periodically.merge.persist(encodedSrc, paths, get_writer).stream

      case MergeOrZip.Zip =>
        val header: Chunk[String] = csvHeader(csvConfiguration)
        (ss: Stream[F, Chunk[Seq[String]]]) =>
          val encodedSrc: Stream[F, Chunk[String]] = if (csvConfiguration.hasHeader) {
            ss.map(ck => header ++ ck.map(csvRow(csvConfiguration)))
          } else {
            ss.map(ck => ck.map(csvRow(csvConfiguration)))
          }
          periodically.zip.persist(get_writer, encodedSrc.zip(paths)).stream
    }
  }

  def kantan(f: Endo[CsvConfiguration]): Pipe[F, Chunk[Seq[String]], TickedValue[Int]] =
    kantan(f(CsvConfiguration.rfc))

  val kantan: Pipe[F, Chunk[Seq[String]], TickedValue[Int]] =
    kantan(CsvConfiguration.rfc)

  // text
  val text: Pipe[F, Chunk[String], TickedValue[Int]] = {
    def get_writer(url: Path): Resource[F, HadoopWriter[F, String]] =
      HadoopWriter.stringR(configuration, url)

    mergeOrZip match {
      case MergeOrZip.Merge =>
        (ss: Stream[F, Chunk[String]]) => periodically.merge.persist(ss, paths, get_writer).stream
      case MergeOrZip.Zip =>
        (ss: Stream[F, Chunk[String]]) => periodically.zip.persist(get_writer, ss.zip(paths)).stream
    }
  }

  /** Any proto in serialized form must be <2GiB, as that is the maximum size supported by all
    * implementations. It’s recommended to bound request and response sizes.
    *
    * https://protobuf.dev/programming-guides/proto-limits/#total
    */
  val protobuf: Pipe[F, Chunk[GeneratedMessage], TickedValue[Int]] = {
    def get_writer(url: Path): Resource[F, HadoopWriter[F, GeneratedMessage]] =
      HadoopWriter.protobufR(configuration, url)

    mergeOrZip match {
      case MergeOrZip.Merge =>
        (ss: Stream[F, Chunk[GeneratedMessage]]) => periodically.merge.persist(ss, paths, get_writer).stream
      case MergeOrZip.Zip =>
        (ss: Stream[F, Chunk[GeneratedMessage]]) => periodically.zip.persist(get_writer, ss.zip(paths)).stream
    }
  }
}

private object FileRotateSink {
  def apply[F[_]: Async](
    mergeOrZip: MergeOrZip,
    configuration: Configuration,
    paths: Stream[F, TickedValue[Url]]): FileRotateSink[F] =
    new FileRotateSink(
      mergeOrZip,
      configuration,
      paths.map(tv => TickedValue(tv.tick, toHadoopPath(tv.value))))
}
