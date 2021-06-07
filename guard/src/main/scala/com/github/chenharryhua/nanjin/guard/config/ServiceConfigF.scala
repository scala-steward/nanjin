package com.github.chenharryhua.nanjin.guard.config

import cats.Functor
import higherkindness.droste.data.Fix
import higherkindness.droste.{scheme, Algebra}
import monocle.macros.Lenses

import scala.concurrent.duration._

@Lenses final case class NJHealthCheck private (interval: FiniteDuration, isEnabled: Boolean)

@Lenses final case class ServiceParams private (
  healthCheck: NJHealthCheck,
  retryPolicy: NJRetryPolicy,
  startUpEventDelay: FiniteDuration, // delay to sent out ServiceStarted event
  isNormalStop: Boolean // treat stop event as normal stop or abnormal stop
)

object ServiceParams {

  def default: ServiceParams =
    ServiceParams(
      healthCheck = NJHealthCheck(8.hours, isEnabled = true),
      retryPolicy = ConstantDelay(30.seconds),
      startUpEventDelay = 15.seconds,
      isNormalStop = false
    )
}

sealed private[guard] trait ServiceConfigF[F]

private object ServiceConfigF {
  implicit val functorServiceConfigF: Functor[ServiceConfigF] = cats.derived.semiauto.functor[ServiceConfigF]

  final case class InitParams[K]() extends ServiceConfigF[K]
  final case class WithHealthCheckInterval[K](value: FiniteDuration, cont: K) extends ServiceConfigF[K]
  final case class WithHealthCheckFlag[K](value: Boolean, cont: K) extends ServiceConfigF[K]
  final case class WithRetryPolicy[K](value: NJRetryPolicy, cont: K) extends ServiceConfigF[K]

  final case class WithStartUpDelay[K](value: FiniteDuration, cont: K) extends ServiceConfigF[K]

  final case class WithNormalStop[K](value: Boolean, cont: K) extends ServiceConfigF[K]

  val algebra: Algebra[ServiceConfigF, ServiceParams] =
    Algebra[ServiceConfigF, ServiceParams] {
      case InitParams()                  => ServiceParams.default
      case WithHealthCheckInterval(v, c) => ServiceParams.healthCheck.composeLens(NJHealthCheck.interval).set(v)(c)
      case WithHealthCheckFlag(v, c)     => ServiceParams.healthCheck.composeLens(NJHealthCheck.isEnabled).set(v)(c)
      case WithRetryPolicy(v, c)         => ServiceParams.retryPolicy.set(v)(c)
      case WithStartUpDelay(v, c)        => ServiceParams.startUpEventDelay.set(v)(c)
      case WithNormalStop(v, c)          => ServiceParams.isNormalStop.set(v)(c)
    }
}

final case class ServiceConfig private (value: Fix[ServiceConfigF]) {
  import ServiceConfigF._

  def withHealthCheckInterval(interval: FiniteDuration): ServiceConfig =
    ServiceConfig(Fix(WithHealthCheckInterval(interval, value)))

  def withHealthCheckDisabled: ServiceConfig =
    ServiceConfig(Fix(WithHealthCheckFlag(value = false, value)))

  def withStartUpDelay(delay: FiniteDuration): ServiceConfig =
    ServiceConfig(Fix(WithStartUpDelay(delay, value)))

  def withConstantDelay(delay: FiniteDuration): ServiceConfig =
    ServiceConfig(Fix(WithRetryPolicy(ConstantDelay(delay), value)))

  def withNormalStop: ServiceConfig =
    ServiceConfig(Fix(WithNormalStop(value = true, value)))

  def evalConfig: ServiceParams = scheme.cata(algebra).apply(value)
}

private[guard] object ServiceConfig {

  def default: ServiceConfig = new ServiceConfig(Fix(ServiceConfigF.InitParams[Fix[ServiceConfigF]]()))
}
