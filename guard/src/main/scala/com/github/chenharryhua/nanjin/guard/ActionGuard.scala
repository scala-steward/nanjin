package com.github.chenharryhua.nanjin.guard

import cats.Show
import cats.collections.Predicate
import cats.data.{Kleisli, Reader}
import cats.effect.kernel.Temporal
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.common.UpdateConfig
import com.github.chenharryhua.nanjin.guard.action.{ActionRetry, ActionRetryUnit, QuasiSucc, QuasiSuccUnit}
import com.github.chenharryhua.nanjin.guard.config.{ActionConfig, ActionParams}
import com.github.chenharryhua.nanjin.guard.event.*
import fs2.Stream
import io.circe.Encoder
import io.circe.syntax.*
import org.apache.commons.lang3.exception.ExceptionUtils

import java.time.ZoneId

final class ActionGuard[F[_]] private[guard] (
  publisher: EventPublisher[F],
  dispatcher: Dispatcher[F],
  actionConfig: ActionConfig)(implicit F: Temporal[F])
    extends UpdateConfig[ActionConfig, ActionGuard[F]] {

  val params: ActionParams     = actionConfig.evalConfig
  val serviceInfo: ServiceInfo = publisher.serviceInfo
  val zoneId: ZoneId           = params.serviceParams.taskParams.zoneId

  override def updateConfig(f: ActionConfig => ActionConfig): ActionGuard[F] =
    new ActionGuard[F](publisher, dispatcher, f(actionConfig))

  def span(name: String): ActionGuard[F] = updateConfig(_.withSpan(name))

  def trivial: ActionGuard[F] = updateConfig(_.withTrivial)
  def normal: ActionGuard[F]  = updateConfig(_.withNormal)
  def notice: ActionGuard[F]  = updateConfig(_.withNotice)

  def retry[A, B](f: A => F[B]): ActionRetry[F, A, B] =
    new ActionRetry[F, A, B](
      publisher = publisher,
      params = params,
      kfab = Kleisli(f),
      succ = Kleisli(_ => F.pure("")),
      fail = Kleisli(_ => F.pure("")),
      isWorthRetry = Reader(_ => true),
      postCondition = Predicate(_ => true))

  def retry[B](fb: F[B]): ActionRetryUnit[F, B] =
    new ActionRetryUnit[F, B](
      fb = fb,
      publisher = publisher,
      params = params,
      succ = Kleisli(_ => F.pure("")),
      fail = Kleisli(_ => F.pure("")),
      isWorthRetry = Reader(_ => true),
      postCondition = Predicate(_ => true))

  def run[B](fb: F[B]): F[B]             = retry(fb).run
  def run[B](sfb: Stream[F, B]): F[Unit] = run(sfb.compile.drain)

  def passThrough[A: Encoder](a: A, metricName: String): F[Unit] = publisher.passThrough(metricName, a.asJson)
  def unsafePassThrough[A: Encoder](a: A, metricName: String): Unit =
    dispatcher.unsafeRunSync(passThrough(a, metricName))

  def count(num: Long, alertName: String): F[Unit]    = publisher.count(alertName, num)
  def unsafeCount(num: Long, alertName: String): Unit = dispatcher.unsafeRunSync(count(num, alertName))

  def alert[S: Show](msg: S, alertName: String): F[Unit]         = publisher.alert(alertName, msg.show)
  def alert[S: Show](msg: Option[S], alertName: String): F[Unit] = msg.traverse(alert(_, alertName)).void
  def alert(msg: Either[Throwable, ?], alertName: String): F[Unit] =
    alert(msg.leftMap(ex => ExceptionUtils.getStackTrace(ex)).swap.toOption, alertName)
  def unsafeAlert[S: Show](msg: S, alertName: String): Unit         = dispatcher.unsafeRunSync(alert(msg, alertName))
  def unsafeAlert[S: Show](msg: Option[S], alertName: String): Unit = dispatcher.unsafeRunSync(alert(msg, alertName))
  def unsafeAlert(msg: Either[Throwable, ?], alertName: String): Unit =
    dispatcher.unsafeRunSync(alert(msg, alertName))

  // maximum retries
  def max(retries: Int): ActionGuard[F] = updateConfig(_.withMaxRetries(retries))

  def nonStop[B](fb: F[B]): F[Nothing] =
    span("nonStop").trivial
      .updateConfig(_.withNonTermination.withMaxRetries(0))
      .retry(fb)
      .run
      .flatMap[Nothing](_ => F.raiseError(new Exception("never happen")))

  def nonStop[B](sfb: Stream[F, B]): F[Nothing] = nonStop(sfb.compile.drain)

  def quasi[T[_], A, B](ta: T[A])(f: A => F[B]): QuasiSucc[F, T, A, B] =
    new QuasiSucc[F, T, A, B](publisher, params, ta, Kleisli(f))

  def quasi[T[_], B](tfb: T[F[B]]): QuasiSuccUnit[F, T, B] = new QuasiSuccUnit[F, T, B](publisher, params, tfb)
  def quasi[B](bs: F[B]*): QuasiSuccUnit[F, List, B]       = quasi[List, B](bs.toList)
}
