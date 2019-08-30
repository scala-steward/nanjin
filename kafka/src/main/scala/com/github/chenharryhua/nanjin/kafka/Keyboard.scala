package com.github.chenharryhua.nanjin.kafka

import cats.effect.{Concurrent, Sync}
import fs2.Stream
import fs2.concurrent.Signal
import org.jline.terminal.{Terminal, TerminalBuilder}

object Keyboard {
  val pauSe: Char    = 's'
  val Quit: Char     = 'q'
  val Continue: Char = 'c'

  def signal[F[_]: Concurrent]: Stream[F, Signal[F, Option[Char]]] =
    Stream
      .bracket(Sync[F].delay {
        val terminal: Terminal = TerminalBuilder.builder().jna(true).system(true).build()
        terminal.enterRawMode
        (terminal, terminal.reader)
      }) {
        case (terminal, reader) =>
          Sync[F].delay {
            terminal.close()
            reader.close()
          }
      }
      .flatMap { case (_, r) => Stream.repeatEval(Sync[F].delay(r.read().toChar)) }
      .noneTerminate
      .hold(None)

}
