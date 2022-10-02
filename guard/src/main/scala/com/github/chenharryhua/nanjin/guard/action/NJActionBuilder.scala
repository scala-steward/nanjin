package com.github.chenharryhua.nanjin.guard.action

import cats.{Alternative, Traverse}
import cats.data.{Ior, Kleisli}
import cats.effect.kernel.Async
import cats.implicits.{
  catsSyntaxApplicativeError,
  toFoldableOps,
  toFunctorOps,
  toTraverseOps,
  toUnorderedFoldableOps
}
import com.codahale.metrics.MetricRegistry
import com.github.chenharryhua.nanjin.guard.config.ActionParams
import com.github.chenharryhua.nanjin.guard.event.NJEvent
import fs2.concurrent.Channel
import io.circe.Json

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.Try

final class NJActionBuilder[F[_]](
  metricRegistry: MetricRegistry,
  channel: Channel[F, NJEvent],
  actionParams: ActionParams
)(implicit F: Async[F]) {

  // retries
  def retry[Z](fb: F[Z]): NJAction0[F, Z] = // 0 arity
    new NJAction0[F, Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionParams,
      arrow = fb,
      transInput = F.pure(Json.Null),
      transOutput = _ => F.pure(Json.Null),
      isWorthRetry = Kleisli(ex => F.pure(NonFatal(ex)))
    )

  def retry[A, Z](f: A => F[Z]): NJAction[F, A, Z] =
    new NJAction[F, A, Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionParams,
      arrow = f,
      transInput = _ => F.pure(Json.Null),
      transOutput = (_: A, _: Z) => F.pure(Json.Null),
      isWorthRetry = Kleisli(ex => F.pure(NonFatal(ex)))
    )

  def retry[A, B, Z](f: (A, B) => F[Z]): NJAction[F, (A, B), Z] =
    new NJAction[F, (A, B), Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionParams,
      arrow = f.tupled,
      transInput = _ => F.pure(Json.Null),
      transOutput = (_: (A, B), _: Z) => F.pure(Json.Null),
      isWorthRetry = Kleisli(ex => F.pure(NonFatal(ex)))
    )

  def retry[A, B, C, Z](f: (A, B, C) => F[Z]): NJAction[F, (A, B, C), Z] =
    new NJAction[F, (A, B, C), Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionParams,
      arrow = f.tupled,
      transInput = _ => F.pure(Json.Null),
      transOutput = (_: (A, B, C), _: Z) => F.pure(Json.Null),
      isWorthRetry = Kleisli(ex => F.pure(NonFatal(ex)))
    )

  def retry[A, B, C, D, Z](f: (A, B, C, D) => F[Z]): NJAction[F, (A, B, C, D), Z] =
    new NJAction[F, (A, B, C, D), Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionParams,
      arrow = f.tupled,
      transInput = _ => F.pure(Json.Null),
      transOutput = (_: (A, B, C, D), _: Z) => F.pure(Json.Null),
      isWorthRetry = Kleisli(ex => F.pure(NonFatal(ex)))
    )

  def retry[A, B, C, D, E, Z](f: (A, B, C, D, E) => F[Z]): NJAction[F, (A, B, C, D, E), Z] =
    new NJAction[F, (A, B, C, D, E), Z](
      metricRegistry = metricRegistry,
      channel = channel,
      actionParams = actionParams,
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

  def quasi[G[_]: Traverse: Alternative, B](tfb: G[F[B]]): NJAction0[F, Ior[G[Throwable], G[B]]] =
    retry(tfb.traverse(_.attempt).map(_.partitionEither(identity)).map { case (fail, succ) =>
      (fail.size, succ.size) match {
        case (0, _) => Ior.Right(succ)
        case (_, 0) => Ior.left(fail)
        case _      => Ior.Both(fail, succ)
      }
    })

  def quasi[B](fbs: F[B]*): NJAction0[F, Ior[List[Throwable], List[B]]] = quasi[List, B](fbs.toList)

  def quasi[G[_]: Traverse: Alternative, B](
    parallelism: Int,
    tfb: G[F[B]]): NJAction0[F, Ior[G[Throwable], G[B]]] =
    retry(
      F.parTraverseN(parallelism)(tfb)(_.attempt).map(_.partitionEither(identity)).map { case (fail, succ) =>
        (fail.size, succ.size) match {
          case (0, _) => Ior.Right(succ)
          case (_, 0) => Ior.left(fail)
          case _      => Ior.Both(fail, succ)
        }
      })

  def quasi[B](parallelism: Int)(tfb: F[B]*): NJAction0[F, Ior[List[Throwable], List[B]]] =
    quasi[List, B](parallelism, tfb.toList)

}
