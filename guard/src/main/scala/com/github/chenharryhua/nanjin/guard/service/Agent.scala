package com.github.chenharryhua.nanjin.guard.service

import cats.Endo
import cats.effect.kernel.{Async, Ref, Unique}
import cats.effect.Resource
import cats.effect.std.{AtomicCell, Dispatcher, MapRef}
import cats.implicits.toFlatMapOps
import com.codahale.metrics.MetricRegistry
import com.github.chenharryhua.nanjin.guard.{awakeEvery, policies}
import com.github.chenharryhua.nanjin.guard.action.*
import com.github.chenharryhua.nanjin.guard.config.*
import com.github.chenharryhua.nanjin.guard.event.*
import cron4s.CronExpr
import fs2.concurrent.{Channel, SignallingMapRef, SignallingRef}
import fs2.Stream
import natchez.{EntryPoint, Kernel, Span}
import org.typelevel.vault.{Key, Locker, Vault}
import retry.{RetryPolicies, RetryPolicy}

import java.time.{ZoneId, ZonedDateTime}

sealed trait Agent[F[_]] extends EntryPoint[F] {
  def zoneId: ZoneId
  def zonedNow: F[ZonedDateTime]
  def metrics: NJMetrics[F]
  def action(name: String, f: Endo[ActionConfig] = identity): NJActionBuilder[F]
  def broker(brokerName: String): NJBroker[F]
  def alert(alertName: String): NJAlert[F]
  def counter(counterName: String): NJCounter[F]
  def meter(meterName: String): NJMeter[F]
  def histogram(histoName: String): NJHistogram[F]
  def gauge(gaugeName: String): NJGauge[F]
  def ticks(policy: RetryPolicy[F]): Stream[F, Int]
  def ticks(cronExpr: CronExpr, f: Endo[RetryPolicy[F]] = identity): Stream[F, Int]
}

final class GeneralAgent[F[_]] private[service] (
  serviceParams: ServiceParams,
  metricRegistry: MetricRegistry,
  channel: Channel[F, NJEvent],
  entryPoint: Resource[F, EntryPoint[F]],
  signallingMapRef: SignallingMapRef[F, Unique.Token, Option[Locker]],
  mapRef: MapRef[F, Unique.Token, Option[Locker]],
  atomicCell: AtomicCell[F, Vault],
  dispatcher: Dispatcher[F])(implicit F: Async[F])
    extends Agent[F] {

  def root(name: String): Resource[F, Span[F]] =
    entryPoint.flatMap(_.root(name))

  def continue(name: String, kernel: Kernel): Resource[F, Span[F]] =
    entryPoint.flatMap(_.continue(name, kernel))

  def continueOrElseRoot(name: String, kernel: Kernel): Resource[F, Span[F]] =
    entryPoint.flatMap(_.continueOrElseRoot(name, kernel))

  val zoneId: ZoneId = serviceParams.taskParams.zoneId

  val zonedNow: F[ZonedDateTime] = serviceParams.zonedNow[F]

  def action(name: String, f: Endo[ActionConfig] = identity): NJActionBuilder[F] =
    new NJActionBuilder[F](
      actionName = name,
      metricRegistry = metricRegistry,
      channel = channel,
      actionConfig = f(ActionConfig(serviceParams)),
      retryPolicy = RetryPolicies.alwaysGiveUp[F]
    )

  def broker(brokerName: String): NJBroker[F] =
    new NJBroker[F](
      digested = Digested(serviceParams, brokerName),
      metricRegistry = metricRegistry,
      channel = channel,
      serviceParams = serviceParams,
      isError = false,
      isCounting = false,
      dispatcher = dispatcher
    )

  def alert(alertName: String): NJAlert[F] =
    new NJAlert(
      digested = Digested(serviceParams, alertName),
      metricRegistry = metricRegistry,
      channel = channel,
      serviceParams = serviceParams,
      isCounting = false,
      dispatcher = dispatcher
    )

  def counter(counterName: String): NJCounter[F] =
    new NJCounter(digested = Digested(serviceParams, counterName), metricRegistry = metricRegistry)

  def meter(meterName: String): NJMeter[F] =
    new NJMeter[F](
      digested = Digested(serviceParams, meterName),
      metricRegistry = metricRegistry,
      isCounting = false)

  def histogram(histoName: String): NJHistogram[F] =
    new NJHistogram[F](
      digested = Digested(serviceParams, histoName),
      metricRegistry = metricRegistry,
      isCounting = false
    )

  def gauge(gaugeName: String): NJGauge[F] =
    new NJGauge[F](Digested(serviceParams, gaugeName), metricRegistry, dispatcher)

  def ticks(policy: RetryPolicy[F]): Stream[F, Int] = awakeEvery[F](policy)

  def ticks(cronExpr: CronExpr, f: Endo[RetryPolicy[F]] = identity): Stream[F, Int] =
    awakeEvery[F](f(policies.cronBackoff[F](cronExpr, zoneId)))

  lazy val metrics: NJMetrics[F] =
    new NJMetrics[F](channel = channel, metricRegistry = metricRegistry, serviceParams = serviceParams)

  // general agent section

  def signalBox[A](initValue: A): SignallingRef[F, A] = {
    val token = new Unique.Token
    val key   = new Key[A](token)
    SignallingRef.lens[F, Option[Locker], A](signallingMapRef(token))(
      (ol: Option[Locker]) => ol.flatMap(_.unlock(key)).getOrElse(initValue),
      (_: Option[Locker]) => (a: A) => Some(Locker(key, a)))
  }

  def refBox[A](initValue: A): Ref[F, A] = {
    val token = new Unique.Token
    val key   = new Key[A](token)
    Ref.lens(mapRef(token))(
      (ol: Option[Locker]) => ol.flatMap(_.unlock(key)).getOrElse(initValue),
      (_: Option[Locker]) => (a: A) => Some(Locker(key, a)))
  }

  def atomicBox[A](initValue: F[A]): NJAtomicBox[F, A] =
    new NJAtomicBox[F, A](atomicCell, new Key[A](new Unique.Token()), initValue)

  def nonStop[A](sfa: Stream[F, A]): F[Nothing] =
    action("nonStop", _.withoutTiming.withoutCounting.trivial)
      .withRetryPolicy(RetryPolicies.alwaysGiveUp)
      .retry(sfa.compile.drain)
      .run
      .flatMap[Nothing](_ => F.raiseError(ActionException.UnexpectedlyTerminated))
}
