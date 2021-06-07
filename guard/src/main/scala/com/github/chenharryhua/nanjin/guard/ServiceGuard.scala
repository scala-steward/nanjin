package com.github.chenharryhua.nanjin.guard

import cats.effect.syntax.all._
import cats.effect.{Async, Ref}
import cats.syntax.all._
import com.github.chenharryhua.nanjin.guard.action.ActionGuard
import com.github.chenharryhua.nanjin.guard.alert._
import com.github.chenharryhua.nanjin.guard.config.{ActionConfig, ServiceConfig, ServiceParams}
import fs2.Stream
import fs2.concurrent.Channel

import java.util.UUID

/** @example
  *   {{{ val guard = TaskGuard[IO]("appName").service("service-name") val es: Stream[IO,NJEvent] = guard.eventStream{
  *   gd => gd("action-1").retry(IO(1)).run >> IO("other computation") >> gd("action-2").retry(IO(2)).run } }}}
  */

final class ServiceGuard[F[_]](
  serviceName: String,
  appName: String,
  serviceConfig: ServiceConfig,
  actionConfig: ActionConfig) {
  val params: ServiceParams = serviceConfig.evalConfig

  def updateServiceConfig(f: ServiceConfig => ServiceConfig): ServiceGuard[F] =
    new ServiceGuard[F](serviceName, appName, f(serviceConfig), actionConfig)

  def updateActionConfig(f: ActionConfig => ActionConfig): ServiceGuard[F] =
    new ServiceGuard[F](serviceName, appName, serviceConfig, f(actionConfig))

  def eventStream[A](actionGuard: ActionGuard[F] => F[A])(implicit F: Async[F]): Stream[F, NJEvent] =
    for {
      ts <- Stream.eval(F.realTimeInstant)
      serviceInfo: ServiceInfo =
        ServiceInfo(
          serviceName = serviceName,
          appName = appName,
          params = params,
          launchTime = ts
        )
      dailySummaries <- Stream.eval(Ref.of(DailySummaries.zero))
      ssd = ServiceStarted(serviceInfo)
      sos = ServiceStopped(serviceInfo)
      event <- Stream.eval(Channel.unbounded[F, NJEvent]).flatMap { channel =>
        val publisher = Stream.eval {
          val ret = retry.retryingOnAllErrors(
            params.retryPolicy.policy[F],
            (e: Throwable, r) =>
              for {
                _ <- channel.send(ServicePanic(serviceInfo, r, UUID.randomUUID(), NJError(e)))
                _ <- dailySummaries.update(_.incServicePanic)
              } yield ()
          ) {
            (for {
              _ <- channel.send(ssd).delayBy(params.startUpEventDelay).void
              _ <- dailySummaries.get
                .flatMap(ds => channel.send(ServiceHealthCheck(serviceInfo, ds)))
                .delayBy(params.healthCheck.interval)
                .foreverM[Unit]
            } yield ()).background.use(_ =>
              actionGuard(
                new ActionGuard[F](
                  dailySummaries = dailySummaries,
                  channel = channel,
                  actionName = "anonymous",
                  serviceName = serviceName,
                  appName = appName,
                  actionConfig = actionConfig))) *>
              channel.send(sos)
          }
          ret.guarantee(channel.close.void)
        }
        channel.stream.concurrently(publisher)
      }
    } yield event
}
