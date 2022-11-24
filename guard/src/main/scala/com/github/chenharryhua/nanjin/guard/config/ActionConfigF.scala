package com.github.chenharryhua.nanjin.guard.config

import cats.{Functor, Show}
import cats.implicits.{catsSyntaxEq, catsSyntaxPartialOrder}
import higherkindness.droste.{scheme, Algebra}
import higherkindness.droste.data.Fix
import io.circe.generic.JsonCodec
import monocle.macros.Lenses

@JsonCodec @Lenses
final case class ActionParams(
  actionName: String,
  importance: Importance,
  isCounting: Boolean,
  isTiming: Boolean,
  retryPolicy: String, // for display
  serviceParams: ServiceParams) {
  val digested: Digested = Digested(serviceParams, actionName)

  val isCritical: Boolean   = importance === Importance.Critical // Critical
  val isNotice: Boolean     = importance > Importance.Silent // Hight + Critical
  val isNonTrivial: Boolean = importance > Importance.Trivial // Medium + High + Critical
}

object ActionParams {
  implicit val showActionParams: Show[ActionParams] = cats.derived.semiauto.show

  def apply(actionName: String, retryPolicy: String, serviceParams: ServiceParams): ActionParams =
    ActionParams(
      actionName = actionName,
      importance = Importance.Silent,
      isCounting = false,
      isTiming = false,
      retryPolicy = retryPolicy,
      serviceParams = serviceParams)
}

sealed private[guard] trait ActionConfigF[X]

private object ActionConfigF {
  implicit val functorActionConfigF: Functor[ActionConfigF] = cats.derived.semiauto.functor[ActionConfigF]

  final case class InitParams[K]() extends ActionConfigF[K]

  final case class WithImportance[K](value: Importance, cont: K) extends ActionConfigF[K]
  final case class WithTiming[K](value: Boolean, cont: K) extends ActionConfigF[K]
  final case class WithCounting[K](value: Boolean, cont: K) extends ActionConfigF[K]

  def algebra(
    actionName: String,
    serviceParams: ServiceParams,
    retryPolicy: String): Algebra[ActionConfigF, ActionParams] =
    Algebra[ActionConfigF, ActionParams] {
      case InitParams()         => ActionParams(actionName, retryPolicy, serviceParams)
      case WithImportance(v, c) => ActionParams.importance.set(v)(c)
      case WithTiming(v, c)     => ActionParams.isTiming.set(v)(c)
      case WithCounting(v, c)   => ActionParams.isCounting.set(v)(c)
    }
}

final case class ActionConfig private (value: Fix[ActionConfigF], serviceParams: ServiceParams) {
  import ActionConfigF.*

  def trivial: ActionConfig  = ActionConfig(Fix(WithImportance(Importance.Trivial, value)), serviceParams)
  def silent: ActionConfig   = ActionConfig(Fix(WithImportance(Importance.Silent, value)), serviceParams)
  def notice: ActionConfig   = ActionConfig(Fix(WithImportance(Importance.Notice, value)), serviceParams)
  def critical: ActionConfig = ActionConfig(Fix(WithImportance(Importance.Critical, value)), serviceParams)

  def withCounting: ActionConfig    = ActionConfig(Fix(WithCounting(value = true, value)), serviceParams)
  def withTiming: ActionConfig      = ActionConfig(Fix(WithTiming(value = true, value)), serviceParams)
  def withoutCounting: ActionConfig = ActionConfig(Fix(WithCounting(value = false, value)), serviceParams)
  def withoutTiming: ActionConfig   = ActionConfig(Fix(WithTiming(value = false, value)), serviceParams)

  def evalConfig(actionName: String, retryPolicy: String): ActionParams =
    scheme.cata(algebra(actionName, serviceParams, retryPolicy)).apply(value)
}

private[guard] object ActionConfig {

  def apply(serviceParams: ServiceParams): ActionConfig =
    ActionConfig(Fix(ActionConfigF.InitParams[Fix[ActionConfigF]]()), serviceParams)
}
