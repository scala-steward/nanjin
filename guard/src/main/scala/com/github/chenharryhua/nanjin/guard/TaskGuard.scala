package com.github.chenharryhua.nanjin.guard

import cats.effect.Async
import fs2.Stream

import scala.concurrent.duration._

final case class ApplicationName(value: String) extends AnyVal
final case class ServiceName(value: String) extends AnyVal

final case class AlertEveryNRetries(value: Int) extends AnyVal
final case class MaximumRetries(value: Long) extends AnyVal
final case class RetryInterval(value: FiniteDuration) extends AnyVal
final case class HealthCheckInterval(value: FiniteDuration) extends AnyVal

final case class RetryForeverState(
  alertEveryNRetry: AlertEveryNRetries,
  nextRetryIn: FiniteDuration,
  numOfRetries: Int,
  totalDelay: FiniteDuration,
  err: Throwable
)

final class TaskGuard[F[_]] private (
  alert: AlertService[F],
  appName: ApplicationName,
  serviceName: ServiceName,
  alterEveryNRetries: AlertEveryNRetries,
  maximumRetries: MaximumRetries,
  retryInterval: RetryInterval,
  healthCheckInterval: HealthCheckInterval
) {

  def foreverAction[A](action: F[A])(implicit F: Async[F]): F[Unit] =
    new RetryForever[F](alert, appName, serviceName, retryInterval, alterEveryNRetries, healthCheckInterval)
      .foreverAction(action)

  def infiniteStream[A](stream: Stream[F, A])(implicit F: Async[F]): F[Unit] =
    new RetryForever[F](alert, appName, serviceName, retryInterval, alterEveryNRetries, healthCheckInterval)
      .infiniteStream(stream)

  def limitRetry[A](action: F[A])(implicit F: Async[F]): F[A] =
    new LimitRetry[F](alert, appName, serviceName, maximumRetries, retryInterval).limitRetry(action)

}

object TaskGuard {

  def apply[F[_]](applicationName: String, serviceName: String, alertService: AlertService[F]): TaskGuard[F] =
    new TaskGuard[F](
      alertService,
      ApplicationName(applicationName),
      ServiceName(serviceName),
      AlertEveryNRetries(30),
      MaximumRetries(3),
      RetryInterval(10.seconds),
      HealthCheckInterval(20.seconds)
    )
}
