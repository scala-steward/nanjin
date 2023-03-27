package com.github.chenharryhua.nanjin.guard.observers

import cats.{Endo, Monad}
import cats.effect.std.Console
import cats.implicits.toFunctorOps
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.guard.event.NJEvent
import com.github.chenharryhua.nanjin.guard.translators.{Translator, UpdateTranslator}
import fs2.Chunk

import java.time.format.DateTimeFormatter

object console {
  def apply[F[_]: Console: Monad](translator: Translator[F, String]): TextConsole[F] =
    new TextConsole[F](translator)

  def verbose[F[_]: Console: Monad]: TextConsole[F] = apply[F](Translator.verboseText[F])

  def simple[F[_]: Console: Monad]: TextConsole[F] = apply[F](Translator.simpleText[F])

  def json[F[_]: Console: Monad]: TextConsole[F]        = apply[F](Translator.simpleJson.map(_.noSpaces))
  def prettyJson[F[_]: Console: Monad]: TextConsole[F]  = apply[F](Translator.prettyJson.map(_.noSpaces))
  def verboseJson[F[_]: Console: Monad]: TextConsole[F] = apply[F](Translator.verboseJson.map(_.spaces2))

  final class TextConsole[F[_]: Monad](translator: Translator[F, String])(implicit C: Console[F])
      extends (NJEvent => F[Unit]) with UpdateTranslator[F, String, TextConsole[F]] {

    override def updateTranslator(f: Endo[Translator[F, String]]): TextConsole[F] =
      new TextConsole[F](f(translator))

    private[this] val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override def apply(event: NJEvent): F[Unit] =
      translator
        .translate(event)
        .flatMap(_.traverse(evt => C.println(s"${event.timestamp.format(fmt)} Console - $evt")).void)

    def chunk(events: Chunk[NJEvent]): F[Unit] = events.traverse(apply).void
  }
}
