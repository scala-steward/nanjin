package com.github.chenharryhua.nanjin.guard.action

import cats.data.{Kleisli, Reader}
import cats.effect.{Async, Ref}
import cats.syntax.all._
import com.github.chenharryhua.nanjin.guard.alert.{ActionInfo, ActionSucced, NJEvent}
import com.github.chenharryhua.nanjin.guard.config.{ActionConfig, ActionParams}
import fs2.concurrent.Channel
import retry.RetryPolicies

import java.util.UUID

final class ActionRetry[F[_], A, B](
  channel: Channel[F, NJEvent],
  actionName: String,
  serviceName: String,
  applicationName: String,
  config: ActionConfig,
  input: A,
  kleisli: Kleisli[F, A, B],
  succ: Reader[(A, B), String],
  fail: Reader[(A, Throwable), String]) {
  val params: ActionParams = config.evalConfig

  def withSuccNotes(succ: (A, B) => String): ActionRetry[F, A, B] =
    new ActionRetry[F, A, B](
      channel,
      actionName,
      serviceName,
      applicationName,
      config,
      input,
      kleisli,
      Reader(succ.tupled),
      fail)

  def withFailNotes(fail: (A, Throwable) => String): ActionRetry[F, A, B] =
    new ActionRetry[F, A, B](
      channel,
      actionName,
      serviceName,
      applicationName,
      config,
      input,
      kleisli,
      succ,
      Reader(fail.tupled))

  def run(implicit F: Async[F]): F[B] =
    for {
      ref <- Ref.of[F, Int](0)
      ts <- F.realTimeInstant
      actionInfo: ActionInfo =
        ActionInfo(
          actionName = actionName,
          serviceName = serviceName,
          applicationName = applicationName,
          params = params,
          id = UUID.randomUUID(),
          launchTime = ts)
      base = new ActionRetryBase[F, A, B](input, succ, fail)
      res <- retry
        .retryingOnAllErrors[B](
          params.retryPolicy.policy[F].join(RetryPolicies.limitRetries(params.maxRetries)),
          base.onError(actionInfo, channel, ref))(kleisli.run(input))
        .flatTap(b =>
          for {
            count <- ref.get
            now <- F.realTimeInstant
            _ <- channel.send(ActionSucced(actionInfo, now, count, base.succNotes(b)))
          } yield ())
    } yield res
}
