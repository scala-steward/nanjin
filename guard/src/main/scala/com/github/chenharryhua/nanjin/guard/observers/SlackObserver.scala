package com.github.chenharryhua.nanjin.guard.observers

import cats.effect.kernel.{Clock, Concurrent, Resource}
import cats.syntax.all.*
import com.amazonaws.services.sns.model.{PublishRequest, PublishResult}
import com.github.chenharryhua.nanjin.aws.SimpleNotificationService
import com.github.chenharryhua.nanjin.common.aws.SnsArn
import com.github.chenharryhua.nanjin.guard.event.NJEvent
import com.github.chenharryhua.nanjin.guard.event.NJEvent.{
  ActionFail,
  ActionRetry,
  ActionStart,
  ActionSucc,
  ServiceStart
}
import com.github.chenharryhua.nanjin.guard.translators.*
import fs2.{Pipe, Stream}
import io.circe.syntax.*

import java.util.UUID

object SlackObserver {
  def apply[F[_]: Concurrent: Clock](client: Resource[F, SimpleNotificationService[F]]): SlackObserver[F] =
    new SlackObserver[F](client, Translator.slack[F])
}

/** Notes: slack messages [[https://api.slack.com/docs/messages/builder]]
  */

final class SlackObserver[F[_]: Clock](
  client: Resource[F, SimpleNotificationService[F]],
  translator: Translator[F, SlackApp])(implicit F: Concurrent[F])
    extends UpdateTranslator[F, SlackApp, SlackObserver[F]] {

  /** supporters will be notified:
    *
    * ServicePanic
    *
    * ServiceStop
    *
    * ServiceTermination
    */
  def at(supporters: String): SlackObserver[F] = {
    val sp = Translator.servicePanic[F, SlackApp].modify(_.map(_.prependMarkdown(supporters)))
    val st = Translator.serviceStop[F, SlackApp].modify(_.map(_.prependMarkdown(supporters)))
    new SlackObserver[F](client, translator = sp.andThen(st)(translator))
  }

  override def updateTranslator(f: Translator[F, SlackApp] => Translator[F, SlackApp]): SlackObserver[F] =
    new SlackObserver[F](client, translator = f(translator))

  private def publish(
    client: SimpleNotificationService[F],
    snsArn: SnsArn,
    msg: String): F[Either[Throwable, PublishResult]] = {
    val req = new PublishRequest(snsArn.value, msg)
    client.publish(req).attempt
  }

  def observe(snsArn: SnsArn): Pipe[F, NJEvent, NJEvent] = (es: Stream[F, NJEvent]) =>
    for {
      sns <- Stream.resource(client)
      ofm <- Stream.eval(
        F.ref[Map[UUID, ServiceStart]](Map.empty).map(new FinalizeMonitor(translator.translate, _)))
      event <- es
        .evalTap(ofm.monitoring)
        .evalTap(e =>
          translator.filter {
            case ai: ActionStart => ai.actionParams.isCritical
            case ai: ActionSucc  => ai.actionParams.isCritical
            case ai: ActionRetry => ai.actionParams.isNotice
            case ai: ActionFail  => ai.actionParams.isNonTrivial
            case _               => true
          }.translate(e).flatMap(_.traverse(msg => publish(sns, snsArn, msg.asJson.noSpaces))))
        .onFinalizeCase(
          ofm.terminated(_).flatMap(_.traverse(msg => publish(sns, snsArn, msg.asJson.noSpaces))).void)
    } yield event
}
