package com.github.chenharryhua.nanjin.guard.config

import cats.{Functor, Show}
import com.github.chenharryhua.nanjin.common.guard.MaxRetry
import eu.timepit.refined.cats.*
import higherkindness.droste.{scheme, Algebra}
import higherkindness.droste.data.Fix
import io.circe.generic.JsonCodec
import io.circe.refined.*
import monocle.macros.Lenses
import org.typelevel.cats.time.instances.duration

import java.time.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps

@Lenses @JsonCodec final case class AgentParams private (
  importance: Importance,
  isCounting: Boolean, // if counting the action?
  isTiming: Boolean, // if timing the action?
  capDelay: Option[Duration],
  maxRetries: Option[MaxRetry],
  njRetryPolicy: NJRetryPolicy,
  serviceParams: ServiceParams)

private[guard] object AgentParams extends duration {
  implicit val showAgentParams: Show[AgentParams] = cats.derived.semiauto.show[AgentParams]

  def apply(serviceParams: ServiceParams): AgentParams = AgentParams(
    importance = Importance.Medium,
    isCounting = false,
    isTiming = false,
    capDelay = None,
    maxRetries = None,
    njRetryPolicy = NJRetryPolicy.AlwaysGiveUp,
    serviceParams = serviceParams
  )
}

sealed private[guard] trait AgentConfigF[X]

private object AgentConfigF {
  implicit val functorActionConfigF: Functor[AgentConfigF] = cats.derived.semiauto.functor[AgentConfigF]

  final case class InitParams[K](serviceParams: ServiceParams) extends AgentConfigF[K]

  final case class WithCapDelay[K](value: Duration, cont: K) extends AgentConfigF[K]
  final case class WithRetryPolicy[K](policy: NJRetryPolicy, max: Option[MaxRetry], cont: K)
      extends AgentConfigF[K]

  final case class WithImportance[K](value: Importance, cont: K) extends AgentConfigF[K]
  final case class WithTiming[K](value: Boolean, cont: K) extends AgentConfigF[K]
  final case class WithCounting[K](value: Boolean, cont: K) extends AgentConfigF[K]

  val algebra: Algebra[AgentConfigF, AgentParams] =
    Algebra[AgentConfigF, AgentParams] {
      case InitParams(sp) => AgentParams(sp)
      case WithRetryPolicy(p, m, c) =>
        AgentParams.njRetryPolicy.set(p).andThen(AgentParams.maxRetries.set(m))(c)
      case WithCapDelay(v, c)   => AgentParams.capDelay.set(Some(v))(c)
      case WithImportance(v, c) => AgentParams.importance.set(v)(c)
      case WithTiming(v, c)     => AgentParams.isTiming.set(v)(c)
      case WithCounting(v, c)   => AgentParams.isCounting.set(v)(c)
    }
}

final case class AgentConfig private (value: Fix[AgentConfigF]) {
  import AgentConfigF.*

  def withCapDelay(fd: FiniteDuration): AgentConfig = AgentConfig(Fix(WithCapDelay(fd.toJava, value)))

  def withConstantDelay(baseDelay: FiniteDuration, max: MaxRetry): AgentConfig =
    AgentConfig(Fix(WithRetryPolicy(NJRetryPolicy.ConstantDelay(baseDelay.toJava), Some(max), value)))

  def withExponentialBackoff(baseDelay: FiniteDuration, max: MaxRetry): AgentConfig =
    AgentConfig(Fix(WithRetryPolicy(NJRetryPolicy.ExponentialBackoff(baseDelay.toJava), Some(max), value)))

  def withFibonacciBackoff(baseDelay: FiniteDuration, max: MaxRetry): AgentConfig =
    AgentConfig(Fix(WithRetryPolicy(NJRetryPolicy.FibonacciBackoff(baseDelay.toJava), Some(max), value)))

  def withFullJitterBackoff(baseDelay: FiniteDuration, max: MaxRetry): AgentConfig =
    AgentConfig(Fix(WithRetryPolicy(NJRetryPolicy.FullJitter(baseDelay.toJava), Some(max), value)))

  def withAlwaysGiveUp: AgentConfig =
    AgentConfig(Fix(WithRetryPolicy(NJRetryPolicy.AlwaysGiveUp, None, value)))

  def withLowImportance: AgentConfig      = AgentConfig(Fix(WithImportance(Importance.Low, value)))
  def withMediumImportance: AgentConfig   = AgentConfig(Fix(WithImportance(Importance.Medium, value)))
  def withHighImportance: AgentConfig     = AgentConfig(Fix(WithImportance(Importance.High, value)))
  def withCriticalImportance: AgentConfig = AgentConfig(Fix(WithImportance(Importance.Critical, value)))

  def withCounting: AgentConfig    = AgentConfig(Fix(WithCounting(value = true, value)))
  def withTiming: AgentConfig      = AgentConfig(Fix(WithTiming(value = true, value)))
  def withoutCounting: AgentConfig = AgentConfig(Fix(WithCounting(value = false, value)))
  def withoutTiming: AgentConfig   = AgentConfig(Fix(WithTiming(value = false, value)))

  def evalConfig: AgentParams = scheme.cata(algebra).apply(value)
}

private[guard] object AgentConfig {

  def apply(sp: ServiceParams): AgentConfig = AgentConfig(Fix(AgentConfigF.InitParams[Fix[AgentConfigF]](sp)))
}
