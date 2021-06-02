package com.github.chenharryhua.nanjin.guard

import cats.effect.Sync
import cats.syntax.all._
import com.codahale.metrics.MetricRegistry

import java.time.{Duration => JavaDuration}

final class MetricsService[F[_]] private (val metrics: MetricRegistry)(implicit F: Sync[F]) extends AlertService[F] {

  override def alert(event: NJEvent): F[Unit] = event match {
    case ServiceStarted(serviceInfo) =>
      F.blocking(metrics.counter(s"${serviceInfo.metricsKey}.counter.start").inc())
    case ServicePanic(serviceInfo, _, _, _) =>
      F.blocking(metrics.counter(s"${serviceInfo.metricsKey}.counter.panic").inc())
    case ServiceStopped(serviceInfo) =>
      F.blocking(metrics.counter(s"${serviceInfo.metricsKey}.counter.stop").inc())
    case ServiceHealthCheck(serviceInfo) =>
      F.blocking(metrics.counter(s"${serviceInfo.metricsKey}.counter.health-check").inc())
    case ActionRetrying(actionInfo, _, _) =>
      F.blocking(metrics.counter(s"${actionInfo.metricsKey}.counter.retry").inc())
    case ActionFailed(actionInfo, _, endAt, _, _) =>
      F.blocking(metrics.counter(s"${actionInfo.metricsKey}.counter.fail").inc()) >>
        F.blocking(
          metrics
            .timer(s"${actionInfo.metricsKey}.timer.fail")
            .update(JavaDuration.between(actionInfo.launchTime, endAt)))
    case ActionSucced(actionInfo, endAt, _, _) =>
      F.blocking(metrics.counter(s"${actionInfo.metricsKey}.counter.succ").inc()) >>
        F.blocking(
          metrics
            .timer(s"${actionInfo.metricsKey}.timer.succ")
            .update(JavaDuration.between(actionInfo.launchTime, endAt)))
    case ForYouInformation(_, _) => F.unit
  }
}

object MetricsService {
  def apply[F[_]: Sync](metrics: MetricRegistry): MetricsService[F] = new MetricsService[F](metrics)
}