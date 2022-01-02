package com.github.chenharryhua.nanjin.guard.event

import cats.data.Kleisli
import cats.effect.kernel.{Ref, Temporal}
import cats.effect.std.UUIDGen
import cats.implicits.{catsSyntaxApply, toFunctorOps}
import cats.syntax.all.*
import com.codahale.metrics.{MetricFilter, MetricRegistry}
import com.github.chenharryhua.nanjin.guard.config.*
import cron4s.CronExpr
import cron4s.lib.javatime.javaTemporalInstance
import fs2.concurrent.Channel
import io.circe.Json
import retry.RetryDetails
import retry.RetryDetails.WillDelayAndRetry

import java.time.ZonedDateTime
import scala.jdk.CollectionConverters.CollectionHasAsScala

final private[guard] class EventPublisher[F[_]: UUIDGen](
  val serviceParams: ServiceParams,
  val metricRegistry: MetricRegistry,
  lastCountersRef: Ref[F, MetricSnapshot.LastCounters],
  ongoingCriticalActions: Ref[F, Set[ActionInfo]],
  serviceStatus: Ref[F, ServiceStatus],
  channel: Channel[F, NJEvent])(implicit F: Temporal[F]) {

  private val realZonedDateTime: F[ZonedDateTime] =
    for {
      ts <- F.realTimeInstant
    } yield ts.atZone(serviceParams.taskParams.zoneId)

  /** services
    */

  val serviceReStarted: F[Unit] =
    for {
      ts <- realZonedDateTime
      ss <- serviceStatus.updateAndGet(_.goUp(ts))
      _ <- channel.send(ServiceStart(timestamp = ts, serviceStatus = ss, serviceParams = serviceParams))
      _ <- ongoingCriticalActions.set(Set.empty)
    } yield ()

  def servicePanic(retryDetails: RetryDetails, ex: Throwable): F[Unit] =
    for {
      ts <- realZonedDateTime
      ss <- serviceStatus.updateAndGet(_.goDown(ts))
      uuid <- UUIDGen.randomUUID[F]
      _ <- channel.send(ServicePanic(ss, ts, retryDetails, serviceParams, NJError(uuid, ex)))
    } yield ()

  def serviceStopped: F[Unit] =
    for {
      ts <- realZonedDateTime
      ss <- serviceStatus.updateAndGet(_.goDown(ts))
      _ <- channel.send(
        ServiceStop(
          timestamp = ts,
          serviceStatus = ss,
          serviceParams = serviceParams,
          snapshot = MetricSnapshot.full(metricRegistry, serviceParams)
        ))
    } yield ()

  def metricsReport(metricFilter: MetricFilter, metricReportType: MetricReportType): F[Unit] =
    for {
      ts <- realZonedDateTime
      runnings <- ongoingCriticalActions.get
      oldLast <- lastCountersRef.getAndSet(MetricSnapshot.LastCounters(metricRegistry))
      ss <- serviceStatus.get
      _ <- channel.send(
        MetricsReport(
          serviceStatus = ss,
          reportType = metricReportType,
          pendings = runnings.map(PendingAction(_)).toList.sortBy(_.launchTime),
          timestamp = ts,
          serviceParams = serviceParams,
          snapshot = metricReportType.snapshotType match {
            case MetricSnapshotType.Full =>
              MetricSnapshot.full(metricRegistry, serviceParams)
            case MetricSnapshotType.Regular =>
              MetricSnapshot.regular(metricFilter, metricRegistry, serviceParams)
            case MetricSnapshotType.Delta =>
              MetricSnapshot.delta(oldLast, metricFilter, metricRegistry, serviceParams)
          }
        ))
    } yield ()

  /** Reset Counters only
    */
  def metricsReset(cronExpr: Option[CronExpr]): F[Unit] =
    for {
      ts <- realZonedDateTime
      ss <- serviceStatus.get
      msg = cronExpr.flatMap { ce =>
        ce.next(ts).map { next =>
          MetricsReset(
            resetType = MetricResetType.Scheduled(next),
            serviceStatus = ss,
            timestamp = ts,
            serviceParams = serviceParams,
            snapshot = MetricSnapshot.full(metricRegistry, serviceParams)
          )
        }
      }.getOrElse(
        MetricsReset(
          resetType = MetricResetType.Adhoc,
          serviceStatus = ss,
          timestamp = ts,
          serviceParams = serviceParams,
          snapshot = MetricSnapshot.full(metricRegistry, serviceParams)
        ))
      _ <- channel.send(msg)
      _ <- lastCountersRef.set(MetricSnapshot.LastCounters.empty)
    } yield metricRegistry.getCounters().values().asScala.foreach(c => c.dec(c.getCount))

  /** actions
    */

  def actionStart(actionParams: ActionParams): F[ActionInfo] =
    for {
      uuid <- UUIDGen.randomUUID[F]
      ts <- realZonedDateTime
      ss <- serviceStatus.get
      actionInfo = ActionInfo(actionParams, ss, serviceParams, uuid, ts)
      _ <- channel.send(ActionStart(actionInfo)).whenA(actionInfo.actionParams.importance >= Importance.High)
      _ <- ongoingCriticalActions.update(_ + actionInfo).whenA(actionParams.importance === Importance.Critical)
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
      _ <- ongoingCriticalActions
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
      _ <- ongoingCriticalActions
        .update(_ - actionInfo)
        .whenA(actionInfo.actionParams.importance === Importance.Critical)
    } yield ts

  def passThrough(metricName: DigestedName, json: Json, asError: Boolean): F[Unit] =
    for {
      ts <- realZonedDateTime
      ss <- serviceStatus.get
      _ <- channel.send(
        PassThrough(
          name = metricName,
          asError = asError,
          serviceStatus = ss,
          timestamp = ts,
          serviceParams = serviceParams,
          value = json))
    } yield ()

  def alert(metricName: DigestedName, msg: String, importance: Importance): F[Unit] =
    for {
      ts <- realZonedDateTime
      ss <- serviceStatus.get
      _ <- channel.send(
        ServiceAlert(
          name = metricName,
          serviceStatus = ss,
          timestamp = ts,
          importance = importance,
          serviceParams = serviceParams,
          message = msg))
    } yield ()
}
