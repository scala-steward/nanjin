package com.github.chenharryhua.nanjin.guard.action

import cats.data.Kleisli
import cats.effect.kernel.Async
import com.codahale.metrics.MetricRegistry
import com.github.chenharryhua.nanjin.guard.config.ActionConfig
import com.github.chenharryhua.nanjin.guard.event.NJEvent
import fs2.concurrent.Channel
import io.circe.Json

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.Try

final class NJActionBuilder[F[_]](
  name: String,
  metricRegistry: MetricRegistry,
  channel: Channel[F, NJEvent],
  actionConfig: ActionConfig
)(implicit F: Async[F]) {

  // retries
  def retry[Z](fb: F[Z]): NJAction0[F, Z] = // 0 arity
    new NJAction0[F, Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionConfig.evalConfig(name),
      arrow = fb,
      transInput = F.pure(Json.Null),
      transOutput = _ => F.pure(Json.Null),
      isWorthRetry = Kleisli(ex => F.pure(NonFatal(ex)))
    )

  def retry[A, Z](f: A => F[Z]): NJAction[F, A, Z] =
    new NJAction[F, A, Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionConfig.evalConfig(name),
      arrow = f,
      transInput = _ => F.pure(Json.Null),
      transOutput = (_: A, _: Z) => F.pure(Json.Null),
      isWorthRetry = Kleisli(ex => F.pure(NonFatal(ex)))
    )

  def retry[A, B, Z](f: (A, B) => F[Z]): NJAction[F, (A, B), Z] =
    new NJAction[F, (A, B), Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionConfig.evalConfig(name),
      arrow = f.tupled,
      transInput = _ => F.pure(Json.Null),
      transOutput = (_: (A, B), _: Z) => F.pure(Json.Null),
      isWorthRetry = Kleisli(ex => F.pure(NonFatal(ex)))
    )

  def retry[A, B, C, Z](f: (A, B, C) => F[Z]): NJAction[F, (A, B, C), Z] =
    new NJAction[F, (A, B, C), Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionConfig.evalConfig(name),
      arrow = f.tupled,
      transInput = _ => F.pure(Json.Null),
      transOutput = (_: (A, B, C), _: Z) => F.pure(Json.Null),
      isWorthRetry = Kleisli(ex => F.pure(NonFatal(ex)))
    )

  def retry[A, B, C, D, Z](f: (A, B, C, D) => F[Z]): NJAction[F, (A, B, C, D), Z] =
    new NJAction[F, (A, B, C, D), Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionConfig.evalConfig(name),
      arrow = f.tupled,
      transInput = _ => F.pure(Json.Null),
      transOutput = (_: (A, B, C, D), _: Z) => F.pure(Json.Null),
      isWorthRetry = Kleisli(ex => F.pure(NonFatal(ex)))
    )

  def retry[A, B, C, D, E, Z](f: (A, B, C, D, E) => F[Z]): NJAction[F, (A, B, C, D, E), Z] =
    new NJAction[F, (A, B, C, D, E), Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionConfig.evalConfig(name),
      arrow = f.tupled,
      transInput = _ => F.pure(Json.Null),
      transOutput = (_: (A, B, C, D, E), _: Z) => F.pure(Json.Null),
      isWorthRetry = Kleisli(ex => F.pure(NonFatal(ex)))
    )

  // future

  def retryFuture[Z](future: F[Future[Z]]): NJAction0[F, Z] = // 0 arity
    retry(F.fromFuture(future))

  def retryFuture[A, Z](f: A => Future[Z]): NJAction[F, A, Z] =
    retry((a: A) => F.fromFuture(F.delay(f(a))))

  def retryFuture[A, B, Z](f: (A, B) => Future[Z]): NJAction[F, (A, B), Z] =
    retry((a: A, b: B) => F.fromFuture(F.delay(f(a, b))))

  def retryFuture[A, B, C, Z](f: (A, B, C) => Future[Z]): NJAction[F, (A, B, C), Z] =
    retry((a: A, b: B, c: C) => F.fromFuture(F.delay(f(a, b, c))))

  def retryFuture[A, B, C, D, Z](f: (A, B, C, D) => Future[Z]): NJAction[F, (A, B, C, D), Z] =
    retry((a: A, b: B, c: C, d: D) => F.fromFuture(F.delay(f(a, b, c, d))))

  def retryFuture[A, B, C, D, E, Z](f: (A, B, C, D, E) => Future[Z]): NJAction[F, (A, B, C, D, E), Z] =
    retry((a: A, b: B, c: C, d: D, e: E) => F.fromFuture(F.delay(f(a, b, c, d, e))))

  // error-like
  def retry[A](t: Try[A]): NJAction0[F, A] = retry(F.fromTry(t))

  def retry[A](e: Either[Throwable, A]): NJAction0[F, A] = retry(F.fromEither(e))

  // run effect
  def run[A](t: Try[A]): F[A] = retry(t).run

  def run[A](e: Either[Throwable, A]): F[A] = retry(e).run

  def run[A](fb: F[A]): F[A] = retry(fb).run

  def runFuture[A](future: F[Future[A]]): F[A] = retryFuture(future).run

}
