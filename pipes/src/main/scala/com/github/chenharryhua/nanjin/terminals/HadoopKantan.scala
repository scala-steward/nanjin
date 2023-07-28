package com.github.chenharryhua.nanjin.terminals

import cats.effect.kernel.{Async, Resource, Sync}
import cats.effect.std.Hotswap
import com.github.chenharryhua.nanjin.common.ChunkSize
import com.github.chenharryhua.nanjin.common.time.{awakeEvery, Tick}
import fs2.{Pipe, Stream}
import kantan.csv.*
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.compress.zlib.ZlibCompressor.CompressionLevel
import retry.RetryPolicy
import shapeless.ops.hlist.ToTraversable
import shapeless.ops.record.Keys
import shapeless.{HList, LabelledGeneric}

import scala.annotation.nowarn
sealed trait NJHeaderEncoder[A] extends HeaderEncoder[A]

object NJHeaderEncoder {
  implicit def inferNJHeaderEncoder[A, Repr <: HList, KeysRepr <: HList](implicit
    enc: RowEncoder[A],
    @nowarn gen: LabelledGeneric.Aux[A, Repr],
    keys: Keys.Aux[Repr, KeysRepr],
    traversable: ToTraversable.Aux[KeysRepr, List, Symbol]): NJHeaderEncoder[A] =
    new NJHeaderEncoder[A] {
      override def header: Option[Seq[String]] = Some(keys().toList.map(_.name))
      override def rowEncoder: RowEncoder[A]   = enc
    }
}

final class HadoopKantan[F[_]] private (
  configuration: Configuration,
  blockSizeHint: Long,
  chunkSize: ChunkSize,
  compressLevel: CompressionLevel,
  csvConfiguration: CsvConfiguration
) {

  def withChunkSize(cs: ChunkSize): HadoopKantan[F] =
    new HadoopKantan[F](configuration, blockSizeHint, cs, compressLevel, csvConfiguration)

  def withBlockSizeHint(bsh: Long): HadoopKantan[F] =
    new HadoopKantan[F](configuration, bsh, chunkSize, compressLevel, csvConfiguration)

  def withCompressionLevel(cl: CompressionLevel): HadoopKantan[F] =
    new HadoopKantan[F](configuration, blockSizeHint, chunkSize, cl, csvConfiguration)

  def source[A: HeaderDecoder](path: NJPath)(implicit F: Sync[F]): Stream[F, A] =
    for {
      reader <- Stream.resource(HadoopReader.kantan(configuration, csvConfiguration, path.hadoopPath))
      a <- Stream.fromBlockingIterator(reader.iterator, chunkSize.value).rethrow
    } yield a

  def source[A: HeaderDecoder](paths: List[NJPath])(implicit F: Sync[F]): Stream[F, A] =
    paths.foldLeft(Stream.empty.covaryAll[F, A]) { case (s, p) =>
      s ++ source(p)
    }

  def sink[A: NJHeaderEncoder](path: NJPath)(implicit F: Sync[F]): Pipe[F, A, Nothing] = {
    (ss: Stream[F, A]) =>
      Stream
        .resource(
          HadoopWriter
            .kantan[F, A](configuration, compressLevel, blockSizeHint, csvConfiguration, path.hadoopPath))
        .flatMap(w => persist[F, A](w, ss).stream)
  }

  def sink[A: NJHeaderEncoder](policy: RetryPolicy[F])(pathBuilder: Tick => NJPath)(implicit
    F: Async[F]): Pipe[F, A, Nothing] = {
    def getWriter(tick: Tick): Resource[F, HadoopWriter[F, A]] =
      HadoopWriter.kantan[F, A](
        configuration,
        compressLevel,
        blockSizeHint,
        csvConfiguration,
        pathBuilder(tick).hadoopPath)

    val init: Resource[F, (Hotswap[F, HadoopWriter[F, A]], HadoopWriter[F, A])] =
      Hotswap(
        HadoopWriter.kantan[F, A](
          configuration,
          compressLevel,
          blockSizeHint,
          csvConfiguration,
          pathBuilder(Tick.Zero).hadoopPath))

    (ss: Stream[F, A]) =>
      Stream.resource(init).flatMap { case (hotswap, writer) =>
        rotatePersist[F, A](
          getWriter,
          hotswap,
          writer,
          ss.map(Left(_)).mergeHaltL(awakeEvery[F](policy).map(Right(_)))
        ).stream
      }
  }

}

object HadoopKantan {
  def apply[F[_]](hadoopCfg: Configuration, csvCfg: CsvConfiguration): HadoopKantan[F] =
    new HadoopKantan[F](hadoopCfg, BLOCK_SIZE_HINT, CHUNK_SIZE, CompressionLevel.DEFAULT_COMPRESSION, csvCfg)
}