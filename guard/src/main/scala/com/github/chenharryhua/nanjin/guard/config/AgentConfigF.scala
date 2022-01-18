package com.github.chenharryhua.nanjin.guard.config

import cats.{Functor, Show}
import com.github.chenharryhua.nanjin.datetime.instances.*
import eu.timepit.refined.cats.*
import eu.timepit.refined.refineMV
import higherkindness.droste.data.Fix
import higherkindness.droste.{scheme, Algebra}
import io.circe.generic.JsonCodec
import io.circe.generic.auto.*
import io.circe.refined.*
import monocle.macros.Lenses

import scala.concurrent.duration.*

@Lenses @JsonCodec final case class AgentParams private (
  spans: List[Span],
  importance: Importance,
  isCounting: CountAction, // if counting the action?
  isTiming: TimeAction, // if timing the action?
  isExpensive: ExpensiveAction, // if the action take long time to accomplish, like a few minutes or hours?
  retry: ActionRetryParams,
  catalog: Catalog,
  serviceParams: ServiceParams)

private[guard] object AgentParams {
  implicit val showAgentParams: Show[AgentParams] = cats.derived.semiauto.show[AgentParams]

  def apply(serviceParams: ServiceParams): AgentParams = AgentParams(
    spans = Nil,
    importance = Importance.Medium,
    isCounting = CountAction.No,
    isTiming = TimeAction.Yes,
    isExpensive = ExpensiveAction.No,
    retry = ActionRetryParams(
      maxRetries = refineMV(0),
      capDelay = None,
      njRetryPolicy = NJRetryPolicy.ConstantDelay(10.seconds)
    ),
    catalog = refineMV("action"),
    serviceParams = serviceParams
  )
}

sealed private[guard] trait AgentConfigF[F]

private object AgentConfigF {
  implicit val functorActionConfigF: Functor[AgentConfigF] = cats.derived.semiauto.functor[AgentConfigF]

  final case class InitParams[K](serviceParams: ServiceParams) extends AgentConfigF[K]

  final case class WithMaxRetries[K](value: MaxRetry, cont: K) extends AgentConfigF[K]
  final case class WithCapDelay[K](value: FiniteDuration, cont: K) extends AgentConfigF[K]
  final case class WithRetryPolicy[K](value: NJRetryPolicy, cont: K) extends AgentConfigF[K]

  final case class WithSpan[K](value: Span, cont: K) extends AgentConfigF[K]

  final case class WithImportance[K](value: Importance, cont: K) extends AgentConfigF[K]
  final case class WithTiming[K](value: TimeAction, cont: K) extends AgentConfigF[K]
  final case class WithCounting[K](value: CountAction, cont: K) extends AgentConfigF[K]
  final case class WithExpensive[K](value: ExpensiveAction, cont: K) extends AgentConfigF[K]

  final case class WithCatalog[K](value: Catalog, cont: K) extends AgentConfigF[K]

  val algebra: Algebra[AgentConfigF, AgentParams] =
    Algebra[AgentConfigF, AgentParams] {
      case InitParams(sp)        => AgentParams(sp)
      case WithRetryPolicy(v, c) => AgentParams.retry.composeLens(ActionRetryParams.njRetryPolicy).set(v)(c)
      case WithMaxRetries(v, c)  => AgentParams.retry.composeLens(ActionRetryParams.maxRetries).set(v)(c)
      case WithCapDelay(v, c)    => AgentParams.retry.composeLens(ActionRetryParams.capDelay).set(Some(v))(c)
      case WithImportance(v, c)  => AgentParams.importance.set(v)(c)
      case WithSpan(v, c)        => AgentParams.spans.modify(_.appended(v))(c)
      case WithTiming(v, c)      => AgentParams.isTiming.set(v)(c)
      case WithCounting(v, c)    => AgentParams.isCounting.set(v)(c)
      case WithCatalog(v, c)     => AgentParams.catalog.set(v)(c)
      case WithExpensive(v, c)   => AgentParams.isExpensive.set(v)(c)
    }
}

final case class AgentConfig private (value: Fix[AgentConfigF]) {
  import AgentConfigF.*

  def withMaxRetries(num: MaxRetry): AgentConfig     = AgentConfig(Fix(WithMaxRetries(num, value)))
  def withCapDelay(dur: FiniteDuration): AgentConfig = AgentConfig(Fix(WithCapDelay(dur, value)))

  def withConstantDelay(delay: FiniteDuration): AgentConfig =
    AgentConfig(Fix(WithRetryPolicy(NJRetryPolicy.ConstantDelay(delay), value)))

  def withExponentialBackoff(delay: FiniteDuration): AgentConfig =
    AgentConfig(Fix(WithRetryPolicy(NJRetryPolicy.ExponentialBackoff(delay), value)))

  def withFibonacciBackoff(delay: FiniteDuration): AgentConfig =
    AgentConfig(Fix(WithRetryPolicy(NJRetryPolicy.FibonacciBackoff(delay), value)))

  def withFullJitterBackoff(delay: FiniteDuration): AgentConfig =
    AgentConfig(Fix(WithRetryPolicy(NJRetryPolicy.FullJitter(delay), value)))

  def withLowImportance: AgentConfig      = AgentConfig(Fix(WithImportance(Importance.Low, value)))
  def withMediumImportance: AgentConfig   = AgentConfig(Fix(WithImportance(Importance.Medium, value)))
  def withHighImportance: AgentConfig     = AgentConfig(Fix(WithImportance(Importance.High, value)))
  def withCriticalImportance: AgentConfig = AgentConfig(Fix(WithImportance(Importance.Critical, value)))

  def withCounting: AgentConfig    = AgentConfig(Fix(WithCounting(value = CountAction.Yes, value)))
  def withTiming: AgentConfig      = AgentConfig(Fix(WithTiming(value = TimeAction.Yes, value)))
  def withoutCounting: AgentConfig = AgentConfig(Fix(WithCounting(value = CountAction.No, value)))
  def withoutTiming: AgentConfig   = AgentConfig(Fix(WithTiming(value = TimeAction.No, value)))
  def withExpensive(isCostly: Boolean): AgentConfig =
    AgentConfig(Fix(WithExpensive(value = if (isCostly) ExpensiveAction.Yes else ExpensiveAction.No, value)))

  def withSpan(name: Span): AgentConfig = AgentConfig(Fix(WithSpan(name, value)))

  def withCatalog(alias: Catalog): AgentConfig = AgentConfig(Fix(WithCatalog(alias, value)))

  def evalConfig: AgentParams = scheme.cata(algebra).apply(value)
}

private[guard] object AgentConfig {

  def apply(sp: ServiceParams): AgentConfig = AgentConfig(Fix(AgentConfigF.InitParams[Fix[AgentConfigF]](sp)))
}
