package com.github.chenharryhua.nanjin.guard

import cats.Show
import cats.effect.Async
import cats.implicits._
import org.log4s.Logger
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}
import retry.{RetryDetails, RetryPolicies, RetryPolicy, Sleep}

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

final private case class LimitedRetryState(totalRetries: Int, totalDelay: FiniteDuration, err: Throwable, input: String)

final private class LimitRetry[F[_]](
  alertService: AlertService[F],
  slack: Slack,
  times: MaximumRetries,
  interval: RetryInterval) {
  private val logger: Logger = org.log4s.getLogger

  def retryEval[A: Show, B](a: A)(f: A => F[B])(implicit F: Async[F], sleep: Sleep[F]): F[B] = {
    def onError(err: Throwable, details: RetryDetails): F[Unit] =
      details match {
        case WillDelayAndRetry(_, sofar, _) =>
          val msg =
            s"error in service: ${slack.name}, retries so far: $sofar/${times.value}, with input: ${a.show}"
          F.blocking(logger.error(err)(msg))
        case GivingUp(totalRetries, totalDelay) =>
          val msg =
            s"error in service: ${slack.name}, give up after retry $totalRetries times, with input: ${a.show}"
          F.blocking(logger.error(err)(msg)) *>
            alertService.alert(slack.limitAlert(LimitedRetryState(totalRetries, totalDelay, err, a.show)))
      }

    val retryPolicy: RetryPolicy[F] =
      RetryPolicies.limitRetriesByCumulativeDelay[F](
        interval.value.mul(times.value),
        RetryPolicies.constantDelay[F](interval.value)
      )
    retry
      .retryingOnSomeErrors[B](retryPolicy, (e: Throwable) => F.delay(NonFatal(e)), onError)(f(a))
      .flatTap(_ => F.blocking(logger.info(s"${slack.name} successfully processed input: ${a.show}")))
  }
}
