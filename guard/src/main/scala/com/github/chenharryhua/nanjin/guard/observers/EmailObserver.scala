package com.github.chenharryhua.nanjin.guard.observers

import cats.data.NonEmptyList
import cats.effect.kernel.{Async, Resource}
import cats.effect.kernel.Resource.ExitCase
import cats.syntax.all.*
import cats.Endo
import com.amazonaws.services.simpleemail.model.SendEmailResult
import com.github.chenharryhua.nanjin.aws.*
import com.github.chenharryhua.nanjin.common.{ChunkSize, EmailAddr}
import com.github.chenharryhua.nanjin.common.aws.EmailContent
import com.github.chenharryhua.nanjin.guard.event.NJEvent
import com.github.chenharryhua.nanjin.guard.event.NJEvent.ServiceStart
import com.github.chenharryhua.nanjin.guard.translators.{ColorScheme, Translator, UpdateTranslator}
import eu.timepit.refined.auto.*
import fs2.{Chunk, Pipe, Stream}
import org.typelevel.cats.time.instances.all
import scalatags.Text
import scalatags.Text.all.*

import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object EmailObserver {

  def apply[F[_]: Async](client: Resource[F, SimpleEmailService[F]]): EmailObserver[F] =
    new EmailObserver[F](
      client = client,
      chunkSize = ChunkSize(180),
      interval = 12.hours,
      isNewestFirst = true,
      translator = Translator.html[F])
}

final class EmailObserver[F[_]] private (
  client: Resource[F, SimpleEmailService[F]],
  chunkSize: ChunkSize, // number of events in an email
  interval: FiniteDuration, // send out email every interval
  isNewestFirst: Boolean, // the latest event comes first
  translator: Translator[F, Text.TypedTag[String]])(implicit F: Async[F])
    extends UpdateTranslator[F, Text.TypedTag[String], EmailObserver[F]] with all {

  private[this] def copy(
    chunkSize: ChunkSize = chunkSize,
    interval: FiniteDuration = interval,
    isNewestFirst: Boolean = isNewestFirst,
    translator: Translator[F, Text.TypedTag[String]] = translator): EmailObserver[F] =
    new EmailObserver[F](client, chunkSize, interval, isNewestFirst, translator)

  def withInterval(fd: FiniteDuration): EmailObserver[F] = copy(interval = fd)

  def withChunkSize(cs: ChunkSize): EmailObserver[F] = copy(chunkSize = cs)

  def withOldestFirst: EmailObserver[F] = copy(isNewestFirst = false)

  override def updateTranslator(f: Endo[Translator[F, Text.TypedTag[String]]]): EmailObserver[F] =
    copy(translator = f(translator))

  private def publish(
    eventTags: Chunk[(Text.TypedTag[String], ColorScheme)],
    ses: SimpleEmailService[F],
    from: EmailAddr,
    to: NonEmptyList[EmailAddr],
    subject: String): F[Either[Throwable, SendEmailResult]] = {
    val (warns, errors) = eventTags.foldLeft((0, 0)) { case ((w, e), i) =>
      i._2 match {
        case ColorScheme.GoodColor  => (w, e)
        case ColorScheme.InfoColor  => (w, e)
        case ColorScheme.WarnColor  => (w + 1, e)
        case ColorScheme.ErrorColor => (w, e + 1)
      }
    }

    val header: Text.TypedTag[String] = head(tag("style")("""
        td, th {text-align: left; padding: 2px; border: 1px solid;}
        table {
          border-collapse: collapse;
          width: 90%;
        }
      """))

    val notice: Text.TypedTag[String] =
      if ((warns + errors) > 0) h2(style := "color:red")(s"Pay Attention - $errors Errors, $warns Warnings")
      else h2("All Good")

    val text: List[Text.TypedTag[String]] =
      if (isNewestFirst) eventTags.map(tag => hr(tag._1)).toList.reverse
      else eventTags.map(tag => hr(tag._1)).toList

    val content: String = html(
      header,
      body(notice, text, footer(hr(p(b("Events/Max: "), s"${eventTags.size}/$chunkSize"))))).render

    ses.send(EmailContent(from, to, subject, content)).attempt
  }

  def observe(from: EmailAddr, to: NonEmptyList[EmailAddr], subject: String): Pipe[F, NJEvent, Nothing] = {
    (events: Stream[F, NJEvent]) =>
      val computation = for {
        ses <- Stream.resource(client)
        ofm <- Stream.eval(
          F.ref[Map[UUID, ServiceStart]](Map.empty).map(new FinalizeMonitor(translator.translate, _)))
        _ <- events
          .evalTap(ofm.monitoring)
          .evalMap(evt =>
            translator.translate(evt).map(_.map(tags => (tags, ColorScheme.decorate(evt).eval.value))))
          .unNone
          .groupWithin(chunkSize.value, interval)
          .evalTap(publish(_, ses, from, to, subject))
          .onFinalizeCase(exitCase =>
            ofm.terminated(exitCase).flatMap { chk =>
              val tags: Chunk[(Text.TypedTag[String], ColorScheme)] = chk.map(tag =>
                (
                  tag,
                  exitCase match {
                    case ExitCase.Succeeded  => ColorScheme.GoodColor
                    case ExitCase.Errored(_) => ColorScheme.ErrorColor
                    case ExitCase.Canceled   => ColorScheme.ErrorColor
                  }))
              publish(tags, ses, from, to, subject).void
            })
      } yield ()
      computation.drain
  }
}
