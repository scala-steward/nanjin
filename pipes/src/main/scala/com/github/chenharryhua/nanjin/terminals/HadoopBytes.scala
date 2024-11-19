package com.github.chenharryhua.nanjin.terminals

import cats.effect.kernel.{Async, Resource, Sync}
import cats.implicits.toFunctorOps
import com.github.chenharryhua.nanjin.common.ChunkSize
import com.github.chenharryhua.nanjin.common.chrono.{tickStream, Policy, Tick}
import fs2.{Chunk, Pipe, Stream}
import io.lemonlabs.uri.Url
import org.apache.hadoop.conf.Configuration
import squants.information.{Bytes, Information}

import java.io.{InputStream, OutputStream}
import java.time.ZoneId

final class HadoopBytes[F[_]] private (configuration: Configuration) extends HadoopSink[F, Byte] {

  /** @return
    *   a byte stream which is chunked by ''bufferSize'' except the last chunk.
    */
  def source(path: Url, bufferSize: Information)(implicit F: Sync[F]): Stream[F, Byte] =
    HadoopReader.byteS(configuration, toHadoopPath(path), ChunkSize(bufferSize))

  def source(path: Url)(implicit F: Sync[F]): Stream[F, Byte] =
    source(path, Bytes(1024 * 512))

  def inputStream(path: Url)(implicit F: Sync[F]): Resource[F, InputStream] =
    HadoopReader.inputStreamR[F](configuration, toHadoopPath(path))

  def outputStream(path: Url)(implicit F: Sync[F]): Resource[F, OutputStream] =
    HadoopWriter.outputStreamR[F](toHadoopPath(path), configuration)

  // write

  def sink(path: Url)(implicit F: Sync[F]): Pipe[F, Chunk[Byte], Int] = { (ss: Stream[F, Chunk[Byte]]) =>
    Stream
      .resource(HadoopWriter.byteR[F](configuration, toHadoopPath(path)))
      .flatMap(w => ss.evalMap(c => w.write(c).as(c.size)))
  }

  def sink(paths: Stream[F, Url])(implicit F: Async[F]): Pipe[F, Chunk[Byte], Int] = {
    def get_writer(url: Url): Resource[F, HadoopWriter[F, Byte]] =
      HadoopWriter.byteR[F](configuration, toHadoopPath(url))

    (ss: Stream[F, Chunk[Byte]]) => periodically.persist(ss, paths, get_writer)
  }

  def sink(policy: Policy, zoneId: ZoneId)(pathBuilder: Tick => Url)(implicit
    F: Async[F]): Pipe[F, Chunk[Byte], Int] =
    sink(tickStream.fromZero(policy, zoneId).map(pathBuilder))

}

object HadoopBytes {
  def apply[F[_]](cfg: Configuration): HadoopBytes[F] = new HadoopBytes[F](cfg)
}
