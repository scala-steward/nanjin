package com.github.chenharryhua.nanjin.guard.service

import cats.effect.kernel.{Ref, Temporal}
import cats.effect.std.UUIDGen
import cats.implicits.{catsSyntaxApply, toFunctorOps}
import cats.syntax.all.*
import com.codahale.metrics.{Counter, MetricRegistry}
import com.github.chenharryhua.nanjin.guard.action.{realZonedDateTime, servicePanicMRName, serviceRestartMRName}
import com.github.chenharryhua.nanjin.guard.config.*
import com.github.chenharryhua.nanjin.guard.event.*
import fs2.concurrent.Channel
import org.apache.commons.lang3.exception.ExceptionUtils
import retry.RetryDetails

final private[service] class ServiceEventPublisher[F[_]: UUIDGen](
  serviceParams: ServiceParams,
  serviceStatus: Ref[F, ServiceStatus],
  metricRegistry: MetricRegistry,
  ongoings: Ref[F, Set[ActionInfo]],
  channel: Channel[F, NJEvent])(implicit F: Temporal[F]) {
  val panicCounter: Counter   = metricRegistry.counter(servicePanicMRName)
  val restartCounter: Counter = metricRegistry.counter(serviceRestartMRName)

  /** services
    */

  def serviceReStart: F[Unit] =
    for {
      ts <- realZonedDateTime(serviceParams.taskParams.zoneId)
      ss <- serviceStatus.updateAndGet(_.goUp(ts))
      _ <- channel.send(ServiceStart(ss, ts, serviceParams))
      _ <- ongoings.set(Set.empty)
    } yield restartCounter.inc(1)

  def servicePanic(retryDetails: RetryDetails, ex: Throwable): F[Unit] =
    for {
      ts <- realZonedDateTime(serviceParams.taskParams.zoneId)
      ss <- serviceStatus.updateAndGet(_.goDown(ts, retryDetails.upcomingDelay, ExceptionUtils.getMessage(ex)))
      uuid <- UUIDGen.randomUUID[F]
      _ <- channel.send(ServicePanic(ss, ts, retryDetails, serviceParams, NJError(uuid, ex)))
    } yield panicCounter.inc(1)

  def serviceStop: F[Unit] =
    for {
      ts <- realZonedDateTime(serviceParams.taskParams.zoneId)
      ss <- serviceStatus.updateAndGet(_.goDown(ts, None, cause = "service was stopped"))
      _ <- channel.send(
        ServiceStop(
          timestamp = ts,
          serviceStatus = ss,
          serviceParams = serviceParams,
          snapshot = MetricSnapshot.full(metricRegistry, serviceParams)
        ))
    } yield ()
}
