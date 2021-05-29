package com.github.chenharryhua.nanjin.guard

import cats.Show
import cats.derived.auto.show._
import cats.effect.Sync
import cats.syntax.show._
import org.log4s.Logger

import java.time.Instant

final private class LogService[F[_]](implicit F: Sync[F]) extends AlertService[F] {
  private val logger: Logger = org.log4s.getLogger

  implicit private val showInstant: Show[Instant]     = _.toString()
  implicit private val showThrowable: Show[Throwable] = _.getMessage

  override def alert(event: NJEvent): F[Unit] =
    event match {
      case ss: ServiceStarted     => F.blocking(logger.info(ss.show))
      case ss: ServiceHealthCheck => F.blocking(logger.info(ss.show))
      case ss: ActionSucced       => F.blocking(logger.info(ss.show))
      case ss: ForYouInformation  => F.blocking(logger.info(ss.show))

      case ss @ ServicePanic(_, _, error)   => F.blocking(logger.warn(error)(ss.show))
      case ss @ ActionRetrying(_, _, error) => F.blocking(logger.warn(error)(ss.show))

      case ss @ ServiceStopped(info) =>
        if (info.isNormalStop) F.blocking(logger.info(ss.show))
        else F.blocking(logger.error(new Exception("service was abnormally stopped"))(ss.show))

      case ss @ ActionFailed(_, _, _, error) => F.blocking(logger.error(error)(ss.show))
    }
}
