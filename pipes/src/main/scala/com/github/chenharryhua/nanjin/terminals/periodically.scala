package com.github.chenharryhua.nanjin.terminals

import cats.effect.kernel.{Concurrent, Resource}
import cats.effect.std.Hotswap
import com.github.chenharryhua.nanjin.common.chrono.{Tick, TickedValue}
import fs2.{Chunk, Pull, Stream}
import io.lemonlabs.uri.Url
private object periodically {

  /** @tparam F
    *   effect type
    * @tparam A
    *   type of data
    * @tparam R
    *   the getWriter's input type
    * @return
    */
  private def doWork[F[_], A, R](
    currentTick: Tick,
    getWriter: R => Resource[F, HadoopWriter[F, A]],
    hotswap: Hotswap[F, HadoopWriter[F, A]],
    writer: HadoopWriter[F, A],
    merged: Stream[F, Either[Chunk[A], TickedValue[R]]]
  ): Pull[F, TickedValue[Int], Unit] =
    merged.pull.uncons1.flatMap {
      case Some((head, tail)) =>
        head match {
          case Left(data) =>
            Pull.output1(TickedValue(currentTick, data.size)) >>
              Pull.eval(writer.write(data)) >>
              doWork(currentTick, getWriter, hotswap, writer, tail)
          case Right(ticked) =>
            Pull.eval(hotswap.swap(getWriter(ticked.value))).flatMap { writer =>
              doWork(ticked.tick, getWriter, hotswap, writer, tail)
            }
        }
      case None => Pull.done
    }

  def persist[F[_]: Concurrent, A, R](
    data: Stream[F, Chunk[A]],
    ticks: Stream[F, TickedValue[R]],
    getWriter: R => Resource[F, HadoopWriter[F, A]]): Stream[F, TickedValue[Int]] =
    ticks.pull.uncons1.flatMap {
      case Some((head, tail)) => // use the very first tick to build writer and hotswap
        Stream
          .resource(Hotswap(getWriter(head.value)))
          .flatMap { case (hotswap, writer) =>
            doWork[F, A, R](
              head.tick,
              getWriter,
              hotswap,
              writer,
              data.map(Left(_)).mergeHaltBoth(tail.map(Right(_)))
            ).stream
          }
          .pull
          .echo
      case None => Pull.done
    }.stream

  def persistCsvWithHeader[F[_]](
    currentTick: Tick,
    getWriter: Url => Resource[F, HadoopWriter[F, String]],
    hotswap: Hotswap[F, HadoopWriter[F, String]],
    writer: HadoopWriter[F, String],
    ss: Stream[F, Either[Chunk[String], TickedValue[Url]]],
    header: Chunk[String]
  ): Pull[F, TickedValue[Int], Unit] =
    ss.pull.uncons1.flatMap {
      case Some((head, tail)) =>
        head match {
          case Left(data) =>
            Pull.output1(TickedValue(currentTick, data.size)) >>
              Pull.eval(writer.write(data)) >>
              persistCsvWithHeader[F](currentTick, getWriter, hotswap, writer, tail, header)

          case Right(ticked) =>
            Pull.eval(hotswap.swap(getWriter(ticked.value))).flatMap { writer =>
              Pull.eval(writer.write(header)) >>
                persistCsvWithHeader(ticked.tick, getWriter, hotswap, writer, tail, header)
            }
        }
      case None => Pull.done
    }
}
