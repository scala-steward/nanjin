package com.github.chenharryhua.nanjin.guard.action

import cats.Functor
import cats.effect.kernel.Ref
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.guard.event.{ActionInfo, ServiceStatus}

import java.time.{Duration, ZonedDateTime}
import java.util.UUID

final class NJRuntimeInfo[F[_]: Functor] private[guard] (
  serviceStatus: Ref[F, ServiceStatus],
  ongoings: Ref[F, Set[ActionInfo]]) {

  def upTime(now: ZonedDateTime): F[Duration] = serviceStatus.get.map(_.upTime(now))

  def latestCrashDuration: F[Option[Duration]] = serviceStatus.get.map {
    case ServiceStatus.Up(_, _, lastRestartAt, lastCrashAt) =>
      Some(Duration.between(lastCrashAt, lastRestartAt))
    case _: ServiceStatus.Down => None
  }

  def latestCrash: F[ZonedDateTime] = serviceStatus.get.map(_.fold(_.lastCrashAt, _.crashAt))

  def latestRestart: F[Option[ZonedDateTime]] = serviceStatus.get.map(_.fold(u => Some(u.lastRestartAt), _ => None))

  def downCause: F[Option[String]] = serviceStatus.get.map(_.fold(_ => None, d => Some(d.cause)))

  def serviceUUID: F[UUID] = serviceStatus.get.map(_.uuid)

  def isServiceUp: F[Boolean]   = serviceStatus.get.map(_.isUp)
  def isServiceDown: F[Boolean] = serviceStatus.get.map(_.isDown)

  def pendingActions: F[Set[ActionInfo]] = ongoings.get
}