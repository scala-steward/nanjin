package com.github.chenharryhua.nanjin.guard

import cats.data.{Kleisli, Reader}
import cats.effect.kernel.Temporal
import cats.effect.{Async, Ref}
import cats.syntax.all._
import com.github.chenharryhua.nanjin.guard.action.ActionRetry
import com.github.chenharryhua.nanjin.guard.alert.{
  DailySummaries,
  ForYourInformation,
  NJEvent,
  PassThrough,
  ServiceInfo
}
import com.github.chenharryhua.nanjin.guard.config.{ActionConfig, ActionParams}
import fs2.concurrent.Channel
import io.circe.Encoder
import io.circe.syntax._

import java.time.ZoneId

final class ActionGuard[F[_]](
  serviceInfo: ServiceInfo,
  dailySummaries: Ref[F, DailySummaries],
  channel: Channel[F, NJEvent],
  actionName: String,
  actionConfig: ActionConfig) {
  val params: ActionParams = actionConfig.evalConfig

  def apply(actionName: String): ActionGuard[F] =
    new ActionGuard[F](serviceInfo, dailySummaries, channel, actionName, actionConfig)

  def updateConfig(f: ActionConfig => ActionConfig): ActionGuard[F] =
    new ActionGuard[F](serviceInfo, dailySummaries, channel, actionName, f(actionConfig))

  def retry[A, B](input: A)(f: A => F[B]): ActionRetry[F, A, B] =
    new ActionRetry[F, A, B](
      serviceInfo = serviceInfo,
      dailySummaries = dailySummaries,
      channel = channel,
      actionName = actionName,
      params = params,
      input = input,
      kleisli = Kleisli(f),
      succ = Reader(_ => ""),
      fail = Reader(_ => ""))

  def retry[B](fb: F[B]): ActionRetry[F, Unit, B] = retry[Unit, B](())(_ => fb)

  def fyi(msg: String)(implicit F: Temporal[F]): F[Unit] =
    realZonedDateTime(params.serviceParams).flatMap(ts => channel.send(ForYourInformation(ts, msg))).void

  def passThrough[A: Encoder](a: A)(implicit F: Temporal[F]): F[Unit] =
    realZonedDateTime(params.serviceParams).flatMap(ts => channel.send(PassThrough(ts, a.asJson))).void

  // maximum retries
  def max(retries: Int): ActionGuard[F] = updateConfig(_.withMaxRetries(retries))

  // post good news
  def magpie[B](fb: F[B])(f: B => String)(implicit F: Async[F]): F[B] =
    updateConfig(_.withSuccAlertOn.withFailAlertOff).retry(fb).withSuccNotes((_, b) => f(b)).run

  // post bad news
  def croak[B](fb: F[B])(f: Throwable => String)(implicit F: Async[F]): F[B] =
    updateConfig(_.withSuccAlertOff.withFailAlertOn).retry(fb).withFailNotes((_, ex) => f(ex)).run

  def quietly[B](fb: F[B])(implicit F: Async[F]): F[B] =
    updateConfig(_.withSuccAlertOff.withFailAlertOff).run(fb)

  def loudly[B](fb: F[B])(implicit F: Async[F]): F[B] =
    updateConfig(_.withSuccAlertOn.withFailAlertOn).run(fb)

  def run[B](fb: F[B])(implicit F: Async[F]): F[B] = retry[B](fb).run

  def zoneId: ZoneId = params.serviceParams.taskParams.zoneId

}
