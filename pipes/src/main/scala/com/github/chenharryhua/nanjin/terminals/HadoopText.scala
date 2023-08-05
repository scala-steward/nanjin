package com.github.chenharryhua.nanjin.terminals

import cats.effect.kernel.{Async, Resource, Sync}
import cats.effect.std.Hotswap
import com.github.chenharryhua.nanjin.datetime.tickStream
import com.github.chenharryhua.nanjin.datetime.tickStream.Tick
import fs2.text.{lines, utf8}
import fs2.{Pipe, Stream}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.zlib.ZlibCompressor.CompressionLevel
import retry.RetryPolicy
import squants.information.Information

final class HadoopText[F[_]] private (
  configuration: Configuration,
  blockSizeHint: Long,
  bufferSize: Information,
  compressLevel: CompressionLevel) {

  // config

  def withBlockSizeHint(bsh: Long): HadoopText[F] =
    new HadoopText[F](configuration, bsh, bufferSize, compressLevel)

  def withBufferSize(bs: Information): HadoopText[F] =
    new HadoopText[F](configuration, blockSizeHint, bs, compressLevel)

  def withCompressionLevel(cl: CompressionLevel): HadoopText[F] =
    new HadoopText[F](configuration, blockSizeHint, bufferSize, cl)

  // read

  def source(path: NJPath)(implicit F: Sync[F]): Stream[F, String] =
    HadoopReader.byteS[F](configuration, bufferSize, path.hadoopPath).through(utf8.decode).through(lines)

  def source(paths: List[NJPath])(implicit F: Sync[F]): Stream[F, String] =
    paths.foldLeft(Stream.empty.covaryAll[F, String]) { case (s, p) =>
      s ++ source(p)
    }

  // write

  private def getWriterR(path: Path)(implicit F: Sync[F]): Resource[F, HadoopWriter[F, Byte]] =
    HadoopWriter.byteR[F](configuration, compressLevel, blockSizeHint, path)

  def sink(path: NJPath)(implicit F: Sync[F]): Pipe[F, String, Nothing] = { (ss: Stream[F, String]) =>
    Stream
      .resource(getWriterR(path.hadoopPath))
      .flatMap(w => persist[F, Byte](w, ss.intersperse(NEWLINE_SEPARATOR).through(utf8.encode)).stream)
  }

  def sink(policy: RetryPolicy[F])(pathBuilder: Tick => NJPath)(implicit
    F: Async[F]): Pipe[F, String, Nothing] = {
    def getWriter(tick: Tick): Resource[F, HadoopWriter[F, Byte]] =
      getWriterR(pathBuilder(tick).hadoopPath)

    def init(tick: Tick): Resource[F, (Hotswap[F, HadoopWriter[F, Byte]], HadoopWriter[F, Byte])] =
      Hotswap(getWriter(tick))

    // save
    (ss: Stream[F, String]) =>
      Stream.eval(Tick.Zero).flatMap { zero =>
        Stream.resource(init(zero)).flatMap { case (hotswap, writer) =>
          rotatePersist[F, Byte](
            getWriter,
            hotswap,
            writer,
            ss.intersperse(NEWLINE_SEPARATOR)
              .through(utf8.encode)
              .chunks
              .map(Left(_))
              .mergeHaltL(tickStream[F](policy, zero).map(Right(_)))
          ).stream
        }
      }
  }
}

object HadoopText {
  def apply[F[_]](configuration: Configuration): HadoopText[F] =
    new HadoopText[F](configuration, BLOCK_SIZE_HINT, BUFFER_SIZE, CompressionLevel.DEFAULT_COMPRESSION)
}