package com.github.chenharryhua.nanjin.guard.translators

import cats.Applicative
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.guard.event.*

private[translators] object SimpleTextTranslator {

  private def serviceStarted(evt: ServiceStart): String =
    s"""
       |Service (Re)Started
       |Service: ${evt.metricName.metricRepr}
       |Host: ${evt.serviceParams.taskParams.hostName}
       |Up Time: ${fmt.format(evt.upTime)}
       |""".stripMargin

  private def servicePanic[F[_]: Applicative](evt: ServicePanic): String =
    s"""
       |Service Panic
       |Service: ${evt.metricName.metricRepr}
       |Host: ${evt.serviceParams.taskParams.hostName}
       |Cause: ${evt.error.message}
       |""".stripMargin

  private def serviceStopped(evt: ServiceStop): String =
    s"""
       |Service Stopped
       |Service: ${evt.metricName.metricRepr}
       |Host: ${evt.serviceParams.taskParams.hostName}
       |Up Time: ${fmt.format(evt.upTime)}
       |Cause: ${evt.cause.show}
       |""".stripMargin

  private def metricReport(evt: MetricReport): String =
    s"""
       |${evt.reportType.show}
       |Service: ${evt.metricName.metricRepr}
       |Host: ${evt.serviceParams.taskParams.hostName}
       |Up Time: ${fmt.format(evt.upTime)}
       |${evt.snapshot.show}
       |""".stripMargin

  private def metricReset(evt: MetricReset): String =
    s"""
       |${evt.resetType.show}
       |Service: ${evt.metricName.metricRepr}
       |Host: ${evt.serviceParams.taskParams.hostName}
       |Up Time: ${fmt.format(evt.upTime)}
       |${evt.snapshot.show}
       |""".stripMargin

  private def passThrough(evt: PassThrough): String =
    s"""
       |Pass Through
       |Service: ${evt.metricName.metricRepr}
       |Host: ${evt.serviceParams.taskParams.hostName}
       |Message: ${evt.value.noSpaces}
       |""".stripMargin

  private def instantAlert(evt: InstantAlert): String =
    s"""
       |Service Alert
       |Service: ${evt.metricName.metricRepr}
       |Host: ${evt.serviceParams.taskParams.hostName}
       |Alert: ${evt.message}
       |""".stripMargin

  private def actionStart(evt: ActionStart): String =
    s"""
       |${evt.actionInfo.actionParams.startTitle}
       |Service: ${evt.metricName.metricRepr}
       |Host: ${evt.serviceParams.taskParams.hostName}
       |""".stripMargin

  private def actionRetrying(evt: ActionRetry): String =
    s"""
       |${evt.actionInfo.actionParams.retryTitle}
       |Service: ${evt.metricName.metricRepr}
       |Host: ${evt.serviceParams.taskParams.hostName}
       |Took so far: ${fmt.format(evt.took)}
       |Cause: ${evt.error.message}
       |""".stripMargin

  private def actionFailed[F[_]: Applicative](evt: ActionFail): String =
    s"""
       |${evt.actionInfo.actionParams.failedTitle}
       |Service: ${evt.metricName.metricRepr}
       |Host: ${evt.serviceParams.taskParams.hostName}
       |Took: ${fmt.format(evt.took)}
       |Notes: ${evt.notes.value}
       |Cause: ${evt.error.stackTrace}
       |""".stripMargin

  private def actionSucced(evt: ActionSucc): String =
    s"""
       |${evt.actionInfo.actionParams.succedTitle}
       |Service: ${evt.metricName.metricRepr}
       |Host: ${evt.serviceParams.taskParams.hostName}
       |Took: ${fmt.format(evt.took)}
       |Notes: ${evt.notes.value}
       |""".stripMargin

  def apply[F[_]: Applicative]: Translator[F, String] =
    Translator
      .empty[F, String]
      .withServiceStart(serviceStarted)
      .withServiceStop(serviceStopped)
      .withServicePanic(servicePanic[F])
      .withMetricsReport(metricReport)
      .withMetricsReset(metricReset)
      .withPassThrough(passThrough)
      .withInstantAlert(instantAlert)
      .withActionStart(actionStart)
      .withActionRetry(actionRetrying)
      .withActionFail(actionFailed[F])
      .withActionSucc(actionSucced)

}
