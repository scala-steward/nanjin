package com.github.chenharryhua.nanjin.guard.event

import cats.data.Kleisli
import cats.effect.kernel.{Ref, Temporal}
import cats.effect.std.UUIDGen
import cats.implicits.{catsSyntaxApply, toFunctorOps}
import cats.syntax.all.*
import com.codahale.metrics.{MetricFilter, MetricRegistry}
import com.github.chenharryhua.nanjin.guard.config.{ActionParams, DigestedName, Importance, MetricSnapshotType}
import cron4s.CronExpr
import cron4s.lib.javatime.javaTemporalInstance
import fs2.concurrent.Channel
import io.circe.Json
import retry.RetryDetails
import retry.RetryDetails.WillDelayAndRetry

import java.time.ZonedDateTime
import scala.jdk.CollectionConverters.CollectionHasAsScala

final private[guard] class EventPublisher[F[_]: UUIDGen](
  val serviceInfo: ServiceInfo,
  val metricRegistry: MetricRegistry,
  lastCountersRef: Ref[F, MetricSnapshot.LastCounters],
  runningCriticalActions: Ref[F, Set[ActionInfo]],
  channel: Channel[F, NJEvent])(implicit F: Temporal[F]) {

  private val realZonedDateTime: F[ZonedDateTime] =
    F.realTimeInstant.map(_.atZone(serviceInfo.serviceParams.taskParams.zoneId))

  /** services
    */

  val serviceReStarted: F[Unit] =
    realZonedDateTime.flatMap(ts => channel.send(ServiceStart(timestamp = ts, serviceInfo = serviceInfo)).void)

  def servicePanic(retryDetails: RetryDetails, ex: Throwable): F[Unit] =
    for {
      ts <- realZonedDateTime
      uuid <- UUIDGen.randomUUID[F]
      _ <- channel.send(ServicePanic(serviceInfo, ts, retryDetails, NJError(uuid, ex)))
    } yield ()

  def serviceStopped: F[Unit] =
    for {
      ts <- realZonedDateTime
      _ <- channel.send(
        ServiceStop(
          timestamp = ts,
          serviceInfo = serviceInfo,
          snapshot = MetricSnapshot.full(metricRegistry, serviceInfo.serviceParams)
        ))
    } yield ()

  def metricsReport(metricFilter: MetricFilter, metricReportType: MetricReportType): F[Unit] =
    for {
      ts <- realZonedDateTime
      newLast = MetricSnapshot.LastCounters(metricRegistry)
      runnings <- runningCriticalActions.get
      oldLast <- lastCountersRef.get
      _ <- channel.send(
        MetricsReport(
          serviceInfo = serviceInfo,
          reportType = metricReportType,
          runnings = runnings.toList,
          timestamp = ts,
          snapshot = metricReportType.snapshotType match {
            case MetricSnapshotType.Full =>
              MetricSnapshot.full(metricRegistry, serviceInfo.serviceParams)
            case MetricSnapshotType.Regular =>
              MetricSnapshot.regular(metricFilter, metricRegistry, serviceInfo.serviceParams)
            case MetricSnapshotType.Delta =>
              MetricSnapshot.delta(oldLast, metricFilter, metricRegistry, serviceInfo.serviceParams)
          }
        ))
      _ <- lastCountersRef.update(_ => newLast)
    } yield ()

  /** Reset Counters only
    */
  def metricsReset(cronExpr: Option[CronExpr]): F[Unit] =
    for {
      ts <- realZonedDateTime
      msg = cronExpr.flatMap { ce =>
        ce.next(ts).map { next =>
          MetricsReset(
            resetType = MetricResetType.Scheduled(next),
            serviceInfo = serviceInfo,
            timestamp = ts,
            snapshot = MetricSnapshot.full(metricRegistry, serviceInfo.serviceParams)
          )
        }
      }.getOrElse(
        MetricsReset(
          resetType = MetricResetType.Adhoc,
          serviceInfo = serviceInfo,
          timestamp = ts,
          snapshot = MetricSnapshot.full(metricRegistry, serviceInfo.serviceParams)
        ))
      _ <- channel.send(msg)
      _ <- lastCountersRef.update(_ => MetricSnapshot.LastCounters.empty)
    } yield metricRegistry.getCounters().values().asScala.foreach(c => c.dec(c.getCount))

  /** actions
    */

  def actionStart(actionParams: ActionParams): F[ActionInfo] =
    for {
      uuid <- UUIDGen.randomUUID[F]
      ts <- realZonedDateTime
      actionInfo = ActionInfo(actionParams, serviceInfo, uuid, ts)
      _ <- channel.send(ActionStart(actionInfo)).whenA(actionInfo.actionParams.importance >= Importance.High)
      _ <- runningCriticalActions.update(_ + actionInfo).whenA(actionParams.importance === Importance.Critical)
    } yield actionInfo

  def actionRetrying(
    actionInfo: ActionInfo,
    retryCount: Ref[F, Int],
    willDelayAndRetry: WillDelayAndRetry,
    ex: Throwable
  ): F[Unit] =
    for {
      ts <- realZonedDateTime
      uuid <- UUIDGen.randomUUID[F]
      _ <- channel.send(
        ActionRetry(
          actionInfo = actionInfo,
          timestamp = ts,
          willDelayAndRetry = willDelayAndRetry,
          error = NJError(uuid, ex)))
      _ <- retryCount.update(_ + 1)
    } yield ()

  def actionSucced[A, B](
    actionInfo: ActionInfo,
    retryCount: Ref[F, Int],
    input: A,
    output: F[B],
    buildNotes: Kleisli[F, (A, B), String]): F[ZonedDateTime] =
    for {
      ts <- realZonedDateTime
      result <- output
      num <- retryCount.get
      notes <- buildNotes.run((input, result))
      _ <- channel
        .send(ActionSucc(actionInfo = actionInfo, timestamp = ts, numRetries = num, notes = Notes(notes)))
        .whenA(actionInfo.actionParams.importance >= Importance.High)
      _ <- runningCriticalActions
        .update(_ - actionInfo)
        .whenA(actionInfo.actionParams.importance === Importance.Critical)
    } yield ts

  def actionFailed[A](
    actionInfo: ActionInfo,
    retryCount: Ref[F, Int],
    input: A,
    ex: Throwable,
    buildNotes: Kleisli[F, (A, Throwable), String]
  ): F[ZonedDateTime] =
    for {
      ts <- realZonedDateTime
      uuid <- UUIDGen.randomUUID[F]
      numRetries <- retryCount.get
      notes <- buildNotes.run((input, ex))
      _ <- channel.send(
        ActionFail(
          actionInfo = actionInfo,
          timestamp = ts,
          numRetries = numRetries,
          notes = Notes(notes),
          error = NJError(uuid, ex)))
      _ <- runningCriticalActions
        .update(_ - actionInfo)
        .whenA(actionInfo.actionParams.importance === Importance.Critical)
    } yield ts

  def passThrough(metricName: DigestedName, json: Json, asError: Boolean): F[Unit] =
    for {
      ts <- realZonedDateTime
      _ <- channel.send(
        PassThrough(name = metricName, asError = asError, serviceInfo = serviceInfo, timestamp = ts, value = json))
    } yield ()

  def alert(metricName: DigestedName, msg: String, importance: Importance): F[Unit] =
    for {
      ts <- realZonedDateTime
      _ <- channel.send(
        ServiceAlert(
          name = metricName,
          serviceInfo = serviceInfo,
          timestamp = ts,
          importance = importance,
          message = msg))
    } yield ()
}
