package com.github.chenharryhua.nanjin.terminals

import cats.Endo
import cats.effect.kernel.{Async, Resource, Sync}
import cats.effect.std.Hotswap
import cats.implicits.toFunctorOps
import com.github.chenharryhua.nanjin.common.ChunkSize
import com.github.chenharryhua.nanjin.common.chrono.{tickStream, Policy, Tick, TickStatus}
import fs2.{Chunk, Pipe, Stream}
import io.lemonlabs.uri.Url
import kantan.csv.CsvConfiguration.Header
import kantan.csv.engine.ReaderEngine
import kantan.csv.{CsvConfiguration, CsvReader, ReadResult}
import monocle.syntax.all.*
import org.apache.hadoop.conf.Configuration
import shapeless.ops.hlist.ToTraversable
import shapeless.ops.record.Keys
import shapeless.{HList, LabelledGeneric}

import java.io.InputStreamReader
import java.time.ZoneId
import scala.annotation.nowarn

sealed trait CsvHeaderOf[A] {
  def header: Header.Explicit
  final def modify(f: Endo[String]): Header.Explicit =
    header.focus(_.header).modify(_.map(f))
}

object CsvHeaderOf {
  def apply[A](implicit ev: CsvHeaderOf[A]): ev.type = ev

  implicit def inferKantanCsvHeader[A, Repr <: HList, KeysRepr <: HList](implicit
    @nowarn gen: LabelledGeneric.Aux[A, Repr],
    keys: Keys.Aux[Repr, KeysRepr],
    traversable: ToTraversable.Aux[KeysRepr, List, Symbol]): CsvHeaderOf[A] =
    new CsvHeaderOf[A] {
      override val header: Header.Explicit = Header.Explicit(keys().toList.map(_.name))
    }
}

final class HadoopKantan[F[_]] private (
  configuration: Configuration,
  csvConfiguration: CsvConfiguration
) extends HadoopSink[F, Seq[String]] {

  // read

  def source(path: Url, chunkSize: ChunkSize)(implicit F: Sync[F]): Stream[F, Seq[String]] =
    HadoopReader.inputStreamS[F](configuration, toHadoopPath(path)).flatMap { is =>
      val reader: CsvReader[ReadResult[Seq[String]]] = {
        val cr = ReaderEngine.internalCsvReaderEngine.readerFor(new InputStreamReader(is), csvConfiguration)
        if (csvConfiguration.hasHeader) cr.drop(1) else cr
      }

      Stream.fromBlockingIterator[F](reader.iterator, chunkSize.value).rethrow
    }

  // write

  def sink(path: Url)(implicit F: Sync[F]): Pipe[F, Chunk[Seq[String]], Int] = {
    (ss: Stream[F, Chunk[Seq[String]]]) =>
      Stream.resource(HadoopWriter.csvStringR[F](configuration, toHadoopPath(path))).flatMap { w =>
        val process: Stream[F, Int] =
          ss.evalMap(c => w.write(c.map(csvRow(csvConfiguration))).as(c.size))

        val header: Stream[F, Unit] = Stream.eval(w.write(csvHeader(csvConfiguration)))

        if (csvConfiguration.hasHeader) header >> process else process
      }
  }

  def sink(policy: Policy, zoneId: ZoneId)(pathBuilder: Tick => Url)(implicit
    F: Async[F]): Pipe[F, Chunk[Seq[String]], Int] = {

    def get_writer(tick: Tick): Resource[F, HadoopWriter[F, String]] =
      HadoopWriter.csvStringR[F](configuration, toHadoopPath(pathBuilder(tick)))

    // save
    (ss: Stream[F, Chunk[Seq[String]]]) =>
      Stream.eval(TickStatus.zeroth[F](policy, zoneId)).flatMap { zero =>
        val ticks: Stream[F, Either[Chunk[String], Tick]] = tickStream[F](zero).map(Right(_))

        Stream.resource(Hotswap(get_writer(zero.tick))).flatMap { case (hotswap, writer) =>
          val src: Stream[F, Either[Chunk[String], Tick]] =
            ss.map(_.map(csvRow(csvConfiguration))).map(Left(_))

          if (csvConfiguration.hasHeader) {
            val header: Chunk[String] = csvHeader(csvConfiguration)
            Stream.eval(writer.write(header)) >>
              periodically
                .persistCsvWithHeader[F](
                  get_writer,
                  hotswap,
                  writer,
                  src.mergeHaltBoth(ticks),
                  header
                )
                .stream
          } else {
            periodically
              .persist[F, String](
                get_writer,
                hotswap,
                writer,
                src.mergeHaltBoth(ticks)
              )
              .stream
          }
        }
      }
  }
}

object HadoopKantan {
  def apply[F[_]](hadoopCfg: Configuration, csvCfg: CsvConfiguration): HadoopKantan[F] =
    new HadoopKantan[F](hadoopCfg, csvCfg)
}
