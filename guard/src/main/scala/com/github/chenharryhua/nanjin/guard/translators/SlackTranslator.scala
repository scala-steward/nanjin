package com.github.chenharryhua.nanjin.guard.translators

import cats.Applicative
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.datetime.{DurationFormatter, NJLocalTime, NJLocalTimeRange}
import com.github.chenharryhua.nanjin.guard.config.Importance
import com.github.chenharryhua.nanjin.guard.event.*
import cron4s.lib.javatime.javaTemporalInstance
import io.circe.generic.auto.*
import org.typelevel.cats.time.instances.all

import java.text.NumberFormat
import java.time.temporal.ChronoUnit

final class SlackTranslator[F[_]: Applicative](cfg: SlackConfig[F]) extends all {
  private val goodColor  = "#36a64f"
  private val warnColor  = "#ffd79a"
  private val infoColor  = "#b3d1ff"
  private val errorColor = "#935252"

  private def metricsSection(snapshot: MetricSnapshot): KeyValueSection =
    if (snapshot.show.length <= MessageSizeLimits) {
      KeyValueSection("Metrics", s"```${snapshot.show.replace("-- ", "")}```")
    } else {
      val fmt: NumberFormat = NumberFormat.getIntegerInstance
      val msg: String =
        snapshot.counterMap.filter(_._2 > 0).map(x => s"${x._1}: ${fmt.format(x._2)}").toList.sorted.mkString("\n")
      if (msg.isEmpty)
        KeyValueSection("Counters", "*No counter update*")
      else
        KeyValueSection("Counters", s"```${abbreviate(msg)}```")
    }

  private def serviceStarted(evt: ServiceStart): SlackApp =
    SlackApp(
      username = evt.serviceParams.taskParams.appName,
      attachments = List(
        Attachment(
          color = infoColor,
          blocks = List(
            MarkdownSection(":rocket: *(Re)Started Service*"),
            hostServiceSection(evt.serviceParams),
            JuxtaposeSection(
              first = TextField("Up Time", fmt.format(evt.upTime)),
              second = TextField("Time Zone", evt.serviceParams.taskParams.zoneId.show)
            )
          )
        )
      )
    )

  private def servicePanic(evt: ServicePanic): SlackApp = {
    val upcoming: String = evt.retryDetails.upcomingDelay.map(fmt.format) match {
      case None => "report to developer once you see this message" // never happen
      case Some(ts) =>
        s"restart of which takes place in *$ts* meanwhile the service is dysfunctional."
    }
    SlackApp(
      username = evt.serviceParams.taskParams.appName,
      attachments = List(
        Attachment(
          color = errorColor,
          blocks = List(
            MarkdownSection(
              s":alarm: The service experienced a panic, the *${toOrdinalWords(evt.retryDetails.retriesSoFar + 1L)}* time, $upcoming"),
            hostServiceSection(evt.serviceParams),
            MarkdownSection(s"""|*Up Time:* ${fmt.format(evt.upTime)}
                                |*Restart Policy:* ${evt.serviceParams.retry.policy[F].show}
                                |*Error ID:* ${evt.error.uuid.show}
                                |*Cause:* ${evt.error.message}""".stripMargin)
          )
        )
      )
    )
  }

  private def serviceAlert(evt: ServiceAlert): SlackApp = {
    val (title, color) = evt.importance match {
      case Importance.Critical => (":warning: Error", errorColor)
      case Importance.High     => (":warning: Warning", warnColor)
      case Importance.Medium   => (":information_source: Info", infoColor)
      case Importance.Low      => ("oops. should not happen", errorColor)
    }
    SlackApp(
      username = evt.serviceParams.taskParams.appName,
      attachments = List(
        Attachment(
          color = color,
          blocks = List(MarkdownSection(s"*$title:* ${evt.name.value}"), hostServiceSection(evt.serviceParams)) :::
            (if (evt.message.isEmpty) Nil else List(MarkdownSection(abbreviate(evt.message))))
        )
      )
    )
  }

  private def serviceStopped(evt: ServiceStop): SlackApp =
    SlackApp(
      username = evt.serviceParams.taskParams.appName,
      attachments = List(
        Attachment(
          color = warnColor,
          blocks = List(
            MarkdownSection(s":octagonal_sign: *Service Stopped*."),
            hostServiceSection(evt.serviceParams),
            JuxtaposeSection(
              TextField("Up Time", fmt.format(evt.upTime)),
              TextField("Time Zone", evt.serviceParams.taskParams.zoneId.show)),
            metricsSection(evt.snapshot)
          )
        )
      )
    )

  private def metricsReport(evt: MetricsReport): Option[SlackApp] = {
    val msg = {
      val next =
        nextTime(
          evt.serviceParams.metric.reportSchedule,
          evt.timestamp,
          cfg.reportInterval,
          evt.serviceStatus.launchTime).fold("None")(_.toLocalTime.truncatedTo(ChronoUnit.SECONDS).show)

      SlackApp(
        username = evt.serviceParams.taskParams.appName,
        attachments = List(
          Attachment(
            color = if (evt.snapshot.isContainErrors) warnColor else infoColor,
            blocks = List(
              MarkdownSection(s"*${evt.reportType.show}*"),
              hostServiceSection(evt.serviceParams),
              JuxtaposeSection(TextField("Up Time", fmt.format(evt.upTime)), TextField("Scheduled Next", next)),
              metricsSection(evt.snapshot)
            )
          )
        )
      )
    }
    val isShow =
      isShowMetrics(
        evt.serviceParams.metric.reportSchedule,
        evt.timestamp,
        cfg.reportInterval,
        evt.serviceStatus.launchTime) || evt.reportType.isShow

    if (isShow) Some(msg) else None
  }

  private def metricsReset(evt: MetricsReset): SlackApp =
    evt.resetType match {
      case MetricResetType.Adhoc =>
        SlackApp(
          username = evt.serviceParams.taskParams.appName,
          attachments = List(
            Attachment(
              color = infoColor,
              blocks = List(
                MarkdownSection("*Adhoc Metric Reset*"),
                hostServiceSection(evt.serviceParams),
                JuxtaposeSection(
                  TextField("Up Time", fmt.format(evt.upTime)),
                  TextField(
                    "Scheduled Next",
                    evt.serviceParams.metric.resetSchedule
                      .flatMap(_.next(evt.timestamp.toInstant))
                      .map(
                        _.atZone(evt.serviceParams.taskParams.zoneId).toLocalTime.truncatedTo(ChronoUnit.SECONDS).show)
                      .getOrElse("None")
                  )
                ),
                metricsSection(evt.snapshot)
              )
            )
          )
        )
      case MetricResetType.Scheduled(next) =>
        SlackApp(
          username = evt.serviceParams.taskParams.appName,
          attachments = List(
            Attachment(
              color = infoColor,
              blocks = List(
                MarkdownSection(s"*Scheduled Metric Reset*"),
                hostServiceSection(evt.serviceParams),
                JuxtaposeSection(
                  TextField("Up Time", fmt.format(evt.upTime)),
                  TextField("Scheduled Next", next.toLocalDateTime.truncatedTo(ChronoUnit.SECONDS).show)
                ),
                metricsSection(evt.snapshot)
              )
            )
          )
        )
    }

  private def actionStart(evt: ActionStart): Option[SlackApp] =
    if (evt.actionParams.importance === Importance.Critical)
      Some(
        SlackApp(
          username = evt.serviceParams.taskParams.appName,
          attachments = List(Attachment(
            color = infoColor,
            blocks = List(
              MarkdownSection(s"*${evt.actionParams.startTitle}*"),
              hostServiceSection(evt.actionInfo.serviceParams),
              MarkdownSection(s"*${evt.actionParams.alias} ID:* ${evt.uuid.show}")
            )
          ))
        ))
    else None

  private def actionRetrying(evt: ActionRetry): Option[SlackApp] =
    if (evt.actionParams.importance >= Importance.Medium) {
      Some(
        SlackApp(
          username = evt.serviceParams.taskParams.appName,
          attachments = List(Attachment(
            color = warnColor,
            blocks = List(
              MarkdownSection(s"*${evt.actionParams.retryTitle}*"),
              JuxtaposeSection(
                TextField("Took so far", fmt.format(evt.took)),
                TextField("Retries so far", evt.willDelayAndRetry.retriesSoFar.show)),
              MarkdownSection(s"""|*${evt.actionParams.alias} ID:* ${evt.uuid.show}
                                  |*next retry in: * ${fmt.format(evt.willDelayAndRetry.nextDelay)}
                                  |*policy:* ${evt.actionParams.retry.policy[F].show}""".stripMargin),
              hostServiceSection(evt.serviceParams),
              MarkdownSection(s"*Cause:* ${evt.error.message}")
            )
          ))
        ))
    } else None

  private def actionFailed(evt: ActionFail): Option[SlackApp] =
    if (evt.actionParams.importance >= Importance.Medium) {
      Some(
        SlackApp(
          username = evt.serviceParams.taskParams.appName,
          attachments = List(
            Attachment(
              color = errorColor,
              blocks = List(
                MarkdownSection(s"*${evt.actionParams.failedTitle}*"),
                JuxtaposeSection(TextField("Took", fmt.format(evt.took)), TextField("Retries", evt.numRetries.show)),
                MarkdownSection(s"""|*${evt.actionParams.alias} ID:* ${evt.uuid.show}
                                    |*error ID:* ${evt.error.uuid.show}
                                    |*policy:* ${evt.actionParams.retry.policy[F].show}""".stripMargin),
                hostServiceSection(evt.serviceParams),
                MarkdownSection(s"*Cause:* ${evt.error.message}")
              ) ::: (if (evt.notes.value.isEmpty) Nil
                     else List(MarkdownSection(abbreviate(evt.notes.value))))
            )
          )
        ))
    } else None

  private def actionSucced(evt: ActionSucc): Option[SlackApp] =
    if (evt.actionParams.importance === Importance.Critical) {
      Some(
        SlackApp(
          username = evt.serviceParams.taskParams.appName,
          attachments = List(
            Attachment(
              color = goodColor,
              blocks = List(
                MarkdownSection(s"*${evt.actionParams.succedTitle}*"),
                JuxtaposeSection(TextField("Took", fmt.format(evt.took)), TextField("Retries", evt.numRetries.show)),
                MarkdownSection(s"*${evt.actionParams.alias} ID:* ${evt.uuid.show}"),
                hostServiceSection(evt.serviceParams)
              ) ::: (if (evt.notes.value.isEmpty) Nil
                     else List(MarkdownSection(abbreviate(evt.notes.value))))
            )
          )
        ))
    } else None

  def translator: Translator[F, SlackApp] =
    Translator
      .empty[F, SlackApp]
      .withServiceStart(serviceStarted)
      .withServicePanic(servicePanic)
      .withServiceStop(serviceStopped)
      .withMetricsReport(metricsReport)
      .withMetricsReset(metricsReset)
      .withServiceAlert(serviceAlert)
      .withActionStart(actionStart)
      .withActionRetry(actionRetrying)
      .withActionFail(actionFailed)
      .withActionSucc(actionSucced)
}
