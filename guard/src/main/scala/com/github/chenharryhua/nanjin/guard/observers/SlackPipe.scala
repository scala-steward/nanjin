package com.github.chenharryhua.nanjin.guard.observers

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.aws.SimpleNotificationService
import com.github.chenharryhua.nanjin.common.aws.SnsArn
import com.github.chenharryhua.nanjin.datetime.{DurationFormatter, NJLocalTime, NJLocalTimeRange}
import com.github.chenharryhua.nanjin.guard.event.*
import com.github.chenharryhua.nanjin.guard.translators.*
import fs2.{Pipe, Stream}
import io.circe.syntax.*

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

object SlackPipe {
  def apply[F[_]: Async](snsArn: SnsArn, client: Resource[F, SimpleNotificationService[F]]): SlackPipe[F] =
    new SlackPipe[F](client, snsArn, None, Translator.slack[F])

  def apply[F[_]: Async](snsArn: SnsArn, client: SimpleNotificationService[F]): SlackPipe[F] =
    apply[F](snsArn, Resource.pure[F, SimpleNotificationService[F]](client))
}

/** Notes: slack messages [[https://api.slack.com/docs/messages/builder]]
  */

final class SlackPipe[F[_]](
  client: Resource[F, SimpleNotificationService[F]],
  snsArn: SnsArn,
  metricsInterval: Option[FiniteDuration],
  translator: Translator[F, SlackApp])(implicit F: Async[F])
    extends Pipe[F, NJEvent, NJEvent] with UpdateTranslator[F, SlackApp, SlackPipe[F]] {

  private def copy(
    snsArn: SnsArn = snsArn,
    metricsInterval: Option[FiniteDuration] = metricsInterval,
    translator: Translator[F, SlackApp] = translator): SlackPipe[F] =
    new SlackPipe[F](client, snsArn, metricsInterval, translator)

  def withInterval(fd: FiniteDuration): SlackPipe[F] = copy(metricsInterval = Some(fd))
  def withSnsArn(arn: SnsArn): SlackPipe[F]          = copy(snsArn = arn)

  /** supporters will be notified:
    *
    * ServicePanic
    *
    * ServiceStop
    *
    * ServiceTermination
    */
  def at(supporters: String): SlackPipe[F] = {
    val sp = Translator.servicePanic[F, SlackApp].modify(_.map(_.prependMarkdown(supporters)))
    val st = Translator.serviceStop[F, SlackApp].modify(_.map(_.prependMarkdown(supporters)))
    copy(translator = sp.andThen(st)(translator))
  }

  override def updateTranslator(f: Translator[F, SlackApp] => Translator[F, SlackApp]): SlackPipe[F] =
    copy(translator = f(translator))

  override def apply(es: Stream[F, NJEvent]): Stream[F, NJEvent] =
    for {
      sns <- Stream.resource(client)
      ref <- Stream.eval(F.ref[Map[UUID, ServiceStart]](Map.empty).map(r => new ObserverFinalizeMonitor(translator, r)))
      event <- es
        .evalTap(ref.monitoring)
        .evalTap(e =>
          translator.filter {
            case MetricReport(mrt, sp, _, ts, _, _) =>
              isShowMetrics(sp.metric.reportSchedule, ts, metricsInterval, sp.launchTime) || mrt.isShow
            case ActionStart(ai)            => ai.actionParams.isCritical
            case ActionSucc(ai, _, _, _)    => ai.actionParams.isCritical
            case ActionRetry(ai, _, _, _)   => ai.actionParams.isNotice
            case ActionFail(ai, _, _, _, _) => ai.actionParams.isNonTrivial
            case _                          => true
          }.translate(e).flatMap(_.traverse(sa => sns.publish(snsArn, sa.asJson.noSpaces).attempt)).void)
        .onFinalizeCase(
          ref.terminated(_).flatMap(_.traverse(msg => sns.publish(snsArn, msg.asJson.noSpaces).attempt)).void)
    } yield event
}
