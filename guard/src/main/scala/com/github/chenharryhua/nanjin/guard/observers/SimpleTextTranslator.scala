package com.github.chenharryhua.nanjin.guard.observers

import cats.Applicative
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.guard.event.{NJError, NJEvent}
import com.github.chenharryhua.nanjin.guard.translator.{fmt, textConstants, textHelper, Translator}
import io.circe.Json
import io.circe.syntax.EncoderOps

import scala.util.Try

object SimpleTextTranslator {
  import NJEvent.*
  import textConstants.*
  import textHelper.*

  private def service_event(se: NJEvent): String = {
    val host: String      = s"$CONSTANT_HOST:${hostText(se.serviceParams)}"
    val sn: String        = s"$CONSTANT_SERVICE:${se.serviceParams.serviceName.value}"
    val tn: String        = s"$CONSTANT_TASK:${se.serviceParams.taskName.value}"
    val serviceId: String = s"$CONSTANT_SERVICE_ID:${se.serviceParams.serviceId.show}"
    val uptime: String    = s"$CONSTANT_UPTIME:${uptimeText(se)}"
    s"""|$sn, $tn, $serviceId, 
        |  $host, $uptime""".stripMargin
  }

  private def error_str(err: NJError): String =
    s"""Cause:${err.stack.mkString("\n\t")}"""

  private def notes(js: Json): String = Try(js.spaces2).getOrElse("bad json")

  private def action_event(ae: ActionEvent): String = {
    val mm  = s"$CONSTANT_MEASUREMENT:${ae.actionParams.metricName.measurement}"
    val cfg = s"$CONSTANT_ACTION_ID:${ae.actionID.uniqueToken}"

    s"""  ${service_event(ae)}
       |  $mm, $cfg""".stripMargin
  }

  private def service_started(evt: ServiceStart): String =
    s"""${eventTitle(evt)}
       |  ${service_event(evt)}
       |${evt.serviceParams.asJson.spaces2}
       |""".stripMargin

  private def service_panic(evt: ServicePanic): String =
    s"""${eventTitle(evt)}
       |  ${service_event(evt)}
       |  ${panicText(evt)}
       |  $CONSTANT_POLICY:${evt.serviceParams.servicePolicies.restart}
       |  ${error_str(evt.error)}
       |""".stripMargin

  private def service_stopped(evt: ServiceStop): String =
    s"""${eventTitle(evt)}
       |  ${service_event(evt)}
       |  $CONSTANT_POLICY:${evt.serviceParams.servicePolicies.restart}
       |  $CONSTANT_CAUSE:${stopCause(evt.cause)}
       |""".stripMargin

  private def metric_report(evt: MetricReport): String = {
    val policy = evt.serviceParams.servicePolicies.metricReport.show
    val took   = tookText(evt.took)
    s"""${eventTitle(evt)}
       |  ${service_event(evt)}
       |  $CONSTANT_POLICY:$policy, $CONSTANT_TOOK:$took 
       |${yamlMetrics(evt.snapshot)}
       |""".stripMargin
  }

  private def metric_reset(evt: MetricReset): String = {
    val policy = evt.serviceParams.servicePolicies.metricReport.show
    val took   = tookText(evt.took)

    s"""${eventTitle(evt)}
       |  ${service_event(evt)}
       |  $CONSTANT_POLICY:$policy, $CONSTANT_TOOK:$took
       |${yamlMetrics(evt.snapshot)}
       |""".stripMargin
  }

  private def service_alert(evt: ServiceAlert): String =
    s"""${eventTitle(evt)}
       |  ${service_event(evt)}
       |${evt.message.spaces2}
       |""".stripMargin

  private def action_start(evt: ActionStart): String =
    s"""${eventTitle(evt)}
       |${action_event(evt)}
       |${notes(evt.notes)}
       |""".stripMargin

  private def action_retrying(evt: ActionRetry): String =
    s"""${eventTitle(evt)}
       |${action_event(evt)}
       |  $CONSTANT_SNOOZE:${fmt.format(evt.tick.snooze)}, $CONSTANT_POLICY:${evt.actionParams.retryPolicy}
       |  ${error_str(evt.error)}
       |""".stripMargin

  private def action_fail(evt: ActionFail): String =
    s"""${eventTitle(evt)}
       |${action_event(evt)}
       |  $CONSTANT_POLICY:${evt.actionParams.retryPolicy}
       |  ${error_str(evt.error)}
       |${notes(evt.notes)}
       |""".stripMargin

  private def action_done(evt: ActionDone): String =
    s"""${eventTitle(evt)}
       |${action_event(evt)}, $CONSTANT_TOOK:${tookText(evt.took)}
       |${notes(evt.notes)}
       |""".stripMargin

  def apply[F[_]: Applicative]: Translator[F, String] =
    Translator
      .empty[F, String]
      .withServiceStart(service_started)
      .withServiceStop(service_stopped)
      .withServicePanic(service_panic)
      .withMetricReport(metric_report)
      .withMetricReset(metric_reset)
      .withServiceAlert(service_alert)
      .withActionStart(action_start)
      .withActionRetry(action_retrying)
      .withActionFail(action_fail)
      .withActionDone(action_done)
}
