package com.github.chenharryhua.nanjin.guard.action

import cats.collections.Predicate
import cats.data.{Kleisli, Reader}
import cats.effect.Temporal
import cats.effect.kernel.{Outcome, Ref}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.guard.config.ActionParams
import com.github.chenharryhua.nanjin.guard.event.*
import retry.RetryDetails
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}

// https://www.microsoft.com/en-us/research/wp-content/uploads/2016/07/asynch-exns.pdf
final class ActionRetry[F[_], A, B] private[guard] (
  publisher: EventPublisher[F],
  params: ActionParams,
  kfab: Kleisli[F, A, B],
  succ: Kleisli[F, (A, B), String],
  fail: Kleisli[F, (A, Throwable), String],
  isWorthRetry: Reader[Throwable, Boolean],
  postCondition: Predicate[B])(implicit F: Temporal[F]) {

  def withSuccNotesM(succ: (A, B) => F[String]): ActionRetry[F, A, B] =
    new ActionRetry[F, A, B](
      publisher = publisher,
      params = params,
      kfab = kfab,
      succ = Kleisli(succ.tupled),
      fail = fail,
      isWorthRetry = isWorthRetry,
      postCondition = postCondition)

  def withSuccNotes(f: (A, B) => String): ActionRetry[F, A, B] =
    withSuccNotesM((a: A, b: B) => F.pure(f(a, b)))

  def withFailNotesM(fail: (A, Throwable) => F[String]): ActionRetry[F, A, B] =
    new ActionRetry[F, A, B](
      publisher = publisher,
      params = params,
      kfab = kfab,
      succ = succ,
      fail = Kleisli(fail.tupled),
      isWorthRetry = isWorthRetry,
      postCondition = postCondition)

  def withFailNotes(f: (A, Throwable) => String): ActionRetry[F, A, B] =
    withFailNotesM((a: A, b: Throwable) => F.pure(f(a, b)))

  def withWorthRetry(isWorthRetry: Throwable => Boolean): ActionRetry[F, A, B] =
    new ActionRetry[F, A, B](
      publisher = publisher,
      params = params,
      kfab = kfab,
      succ = succ,
      fail = fail,
      isWorthRetry = Reader(isWorthRetry),
      postCondition = postCondition)

  def withPostCondition(postCondition: B => Boolean): ActionRetry[F, A, B] =
    new ActionRetry[F, A, B](
      publisher = publisher,
      params = params,
      kfab = kfab,
      succ = succ,
      fail = fail,
      isWorthRetry = isWorthRetry,
      postCondition = Predicate(postCondition))

  private def onError(actionInfo: ActionInfo, retryCount: Ref[F, Int])(
    error: Throwable,
    details: RetryDetails): F[Unit] =
    details match {
      case wdr: WillDelayAndRetry => publisher.actionRetrying(actionInfo, params, wdr, error, retryCount)
      case _: GivingUp            => F.unit
    }

  private def handleOutcome(input: A, actionInfo: ActionInfo, retryCount: Ref[F, Int])(
    outcome: Outcome[F, Throwable, B]): F[Unit] =
    outcome match {
      case Outcome.Canceled() =>
        val error = ActionException.ActionCanceledExternally
        publisher.actionFailed[A](actionInfo, params, retryCount, input, error, fail)
      case Outcome.Errored(error) =>
        publisher.actionFailed[A](actionInfo, params, retryCount, input, error, fail)
      case Outcome.Succeeded(output) =>
        publisher.actionSucced[A, B](actionInfo, params, retryCount, input, output, succ)
    }

  def run(input: A): F[B] =
    for {
      retryCount <- F.ref(0) // hold number of retries
      actionInfo <- publisher.actionStart(params)
      res <- F.uncancelable(poll =>
        retry.mtl
          .retryingOnSomeErrors[B](
            params.retry.policy[F],
            isWorthRetry.map(F.pure).run,
            onError(actionInfo, retryCount)
          ) {
            for {
              gate <- F.deferred[Outcome[F, Throwable, B]]
              fiber <- F.start(kfab.run(input).guaranteeCase(gate.complete(_).void))
              oc <- F.onCancel(
                poll(gate.get).flatMap(_.embed(F.raiseError[B](ActionException.ActionCanceledInternally))),
                fiber.cancel)
              _ <- F.raiseError(ActionException.UnexpectedlyTerminated).whenA(!params.isTerminate)
              _ <- F.raiseError(ActionException.PostConditionUnsatisfied).whenA(!postCondition(oc))
            } yield oc
          }
          .guaranteeCase(handleOutcome(input, actionInfo, retryCount)))
    } yield res
}

final class ActionRetryUnit[F[_], B] private[guard] (
  fb: F[B],
  publisher: EventPublisher[F],
  params: ActionParams,
  succ: Kleisli[F, B, String],
  fail: Kleisli[F, Throwable, String],
  isWorthRetry: Reader[Throwable, Boolean],
  postCondition: Predicate[B])(implicit F: Temporal[F]) {

  def withSuccNotesM(succ: B => F[String]): ActionRetryUnit[F, B] =
    new ActionRetryUnit[F, B](
      fb = fb,
      publisher = publisher,
      params = params,
      succ = Kleisli(succ),
      fail = fail,
      isWorthRetry = isWorthRetry,
      postCondition = postCondition)

  def withSuccNotes(f: B => String): ActionRetryUnit[F, B] =
    withSuccNotesM(Kleisli.fromFunction(f).run)

  def withFailNotesM(fail: Throwable => F[String]): ActionRetryUnit[F, B] =
    new ActionRetryUnit[F, B](
      fb = fb,
      publisher = publisher,
      params = params,
      succ = succ,
      fail = Kleisli(fail),
      isWorthRetry = isWorthRetry,
      postCondition = postCondition)

  def withFailNotes(f: Throwable => String): ActionRetryUnit[F, B] =
    withFailNotesM(Kleisli.fromFunction(f).run)

  def withWorthRetry(isWorthRetry: Throwable => Boolean): ActionRetryUnit[F, B] =
    new ActionRetryUnit[F, B](
      fb = fb,
      publisher = publisher,
      params = params,
      succ = succ,
      fail = fail,
      isWorthRetry = Reader(isWorthRetry),
      postCondition = postCondition)

  def withPostCondition(postCondition: B => Boolean): ActionRetryUnit[F, B] =
    new ActionRetryUnit[F, B](
      fb = fb,
      publisher = publisher,
      params = params,
      succ = succ,
      fail = fail,
      isWorthRetry = isWorthRetry,
      postCondition = Predicate(postCondition))

  val run: F[B] =
    new ActionRetry[F, Unit, B](
      publisher = publisher,
      params = params,
      kfab = Kleisli(_ => fb),
      succ = succ.local(_._2),
      fail = fail.local(_._2),
      isWorthRetry = isWorthRetry,
      postCondition = postCondition
    ).run(())
}
