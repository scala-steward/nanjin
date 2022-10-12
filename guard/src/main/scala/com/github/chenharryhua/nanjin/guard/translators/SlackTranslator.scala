package com.github.chenharryhua.nanjin.guard.translators

import cats.{Applicative, Eval}
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.guard.config.Importance
import com.github.chenharryhua.nanjin.guard.event.{MetricSnapshot, NJEvent}
import org.typelevel.cats.time.instances.all

import java.text.NumberFormat
import java.time.Duration

private object SlackTranslator extends all {
  import NJEvent.*

  private def coloring(evt: NJEvent): String = ColorScheme
    .decorate(evt)
    .run {
      case ColorScheme.GoodColor  => Eval.now("#36a64f")
      case ColorScheme.InfoColor  => Eval.now("#b3d1ff")
      case ColorScheme.WarnColor  => Eval.now("#ffd79a")
      case ColorScheme.ErrorColor => Eval.now("#935252")
    }
    .value

  private def metricsSection(snapshot: MetricSnapshot): KeyValueSection =
    if (snapshot.show.length <= MessageSizeLimits) {
      KeyValueSection("Metrics", s"```${snapshot.show.replace("-- ", "")}```")
    } else {
      val fmt: NumberFormat = NumberFormat.getIntegerInstance
      val msg: String =
        snapshot.counterMap
          .filter(_._2 > 0)
          .map(x => s"${x._1}: ${fmt.format(x._2)}")
          .toList
          .sorted
          .mkString("\n")
      if (msg.isEmpty)
        KeyValueSection("Counters", "*No counter update*")
      else
        KeyValueSection("Counters", s"```${abbreviate(msg)}```")
    }
  // don't trim string

// events
  private def serviceStarted(evt: ServiceStart): SlackApp =
    SlackApp(
      username = evt.serviceParams.taskParams.taskName.value,
      attachments = List(
        Attachment(
          color = coloring(evt),
          blocks = List(
            MarkdownSection(s":rocket: *${evt.title}*"),
            hostServiceSection(evt.serviceParams),
            JuxtaposeSection(
              first = TextField("Up Time", fmt.format(evt.upTime)),
              second = TextField("Time Zone", evt.serviceParams.taskParams.zoneId.show)
            ),
            MarkdownSection(s"*Service ID:* ${evt.serviceId.show}"),
            MarkdownSection(evt.serviceParams.brief)
          )
        ))
    )

  private def servicePanic(evt: ServicePanic): SlackApp =
    SlackApp(
      username = evt.serviceParams.taskParams.taskName.value,
      attachments = List(
        Attachment(
          color = coloring(evt),
          blocks = List(
            MarkdownSection(upcomingRestartTimeInterpretation(evt)),
            hostServiceSection(evt.serviceParams),
            MarkdownSection(s"""|*Up Time:* ${fmt.format(evt.upTime)}
                                |*Policy:* ${evt.serviceParams.retryPolicy}
                                |*Service ID:* ${evt.serviceId.show}""".stripMargin),
            KeyValueSection("Cause", s"```${abbreviate(evt.error.stackTrace)}```")
          )
        )
      )
    )

  private def serviceStopped(evt: ServiceStop): SlackApp =
    SlackApp(
      username = evt.serviceParams.taskParams.taskName.value,
      attachments = List(
        Attachment(
          color = coloring(evt),
          blocks = List(
            MarkdownSection(s":octagonal_sign: *${evt.title}*"),
            hostServiceSection(evt.serviceParams),
            JuxtaposeSection(
              TextField("Up Time", fmt.format(evt.upTime)),
              TextField("Time Zone", evt.serviceParams.taskParams.zoneId.show)),
            MarkdownSection(s"""*Service ID:* ${evt.serviceId.show}
                               |*Cause:* ${evt.cause.show}""".stripMargin)
          )
        )
      )
    )

  private def instantAlert(evt: InstantAlert): SlackApp = {
    val title = evt.importance match {
      case Importance.Critical => ":warning: Error"
      case Importance.High     => ":warning: Warning"
      case Importance.Medium   => ":information_source: Info"
      case Importance.Low      => "oops. should not happen"
    }
    val msg: Option[Section] =
      if (evt.message.nonEmpty) Some(MarkdownSection(abbreviate(evt.message))) else None
    SlackApp(
      username = evt.serviceParams.taskParams.taskName.value,
      attachments = List(
        Attachment(
          color = coloring(evt),
          blocks = List(
            MarkdownSection(s"*$title:* ${evt.digested.metricRepr}"),
            hostServiceSection(evt.serviceParams),
            MarkdownSection(s"*Service ID:* ${evt.serviceId.show}")
          ).appendedAll(msg)
        )
      )
    )
  }

  private def metricReport(evt: MetricReport): SlackApp = {
    val nextReport = evt.serviceParams.metric
      .nextReport(evt.timestamp)
      .map(next => localTimeAndDurationStr(evt.timestamp, next)._1)
      .getOrElse("None")

    SlackApp(
      username = evt.serviceParams.taskParams.taskName.value,
      attachments = List(
        Attachment(
          color = coloring(evt),
          blocks = List(
            MarkdownSection(s"*${evt.title}*"),
            hostServiceSection(evt.serviceParams),
            MarkdownSection(s"""${upcomingRestartTimeInterpretation(evt)}
                               |*Next Report at:* $nextReport
                               |*Service ID:* ${evt.serviceId.show}""".stripMargin),
            metricsSection(evt.snapshot)
          )
        ),
        Attachment(color = coloring(evt), blocks = List(MarkdownSection(evt.serviceParams.brief)))
      )
    )
  }

  private def metricReset(evt: MetricReset): SlackApp =
    SlackApp(
      username = evt.serviceParams.taskParams.taskName.value,
      attachments = List(
        Attachment(
          color = coloring(evt),
          blocks = List(
            MarkdownSection(s"*${evt.title}*"),
            hostServiceSection(evt.serviceParams),
            JuxtaposeSection(
              TextField("Up Time", fmt.format(evt.upTime)),
              TextField("Time Zone", evt.serviceParams.taskParams.zoneId.show)
            ),
            MarkdownSection(s"*Service ID:* ${evt.serviceId.show}"),
            metricsSection(evt.snapshot)
          )
        )
      )
    )

  private def traceId(evt: ActionEvent): String = s"${evt.traceId.getOrElse("none")}"

  private def actionStart(evt: ActionStart): SlackApp =
    SlackApp(
      username = evt.serviceParams.taskParams.taskName.value,
      attachments = List(
        Attachment(
          color = coloring(evt),
          blocks = List(
            MarkdownSection(s"*${evt.title}*"),
            hostServiceSection(evt.serviceParams),
            MarkdownSection(s"""*Action Name:* ${evt.digested.metricRepr}
                               |*Action ID:* ${evt.actionId.show}
                               |*Trace ID:* ${traceId(evt)}
                               |*Service ID:* ${evt.serviceId.show}""".stripMargin),
            KeyValueSection("Input", s"""```${abbreviate(evt.actionInfo.input.spaces2)}```""")
          )
        ))
    )

  private def actionRetrying(evt: ActionRetry): SlackApp = {
    val next = fmt.format(Duration.between(evt.timestamp, evt.nextRetryTime))
    val lt   = evt.nextRetryTime.toLocalTime

    SlackApp(
      username = evt.serviceParams.taskParams.taskName.value,
      attachments = List(
        Attachment(
          color = coloring(evt),
          blocks = List(
            MarkdownSection(s"*${evt.title}*"),
            hostServiceSection(evt.serviceParams),
            JuxtaposeSection(
              TextField("Took so far", fmt.format(evt.took)),
              TextField("Retries so far", evt.retriesSoFar.show)),
            MarkdownSection(s"""*Action Name:* ${evt.digested.metricRepr}
                               |*Action ID:* ${evt.actionId.show}
                               |*Trace ID:* ${traceId(evt)}
                               |*The ${toOrdinalWords(evt.retriesSoFar + 1)} retry:* at $lt, in $next
                               |*Policy:* ${evt.actionParams.retryPolicy}
                               |*Service ID:* ${evt.serviceId.show}""".stripMargin),
            KeyValueSection("Cause", s"""```${abbrev(evt.error.message)}```""")
          )
        ))
    )
  }

  private def actionFailed(evt: ActionFail): SlackApp =
    SlackApp(
      username = evt.serviceParams.taskParams.taskName.value,
      attachments = List(
        Attachment(
          color = coloring(evt),
          blocks = List(
            MarkdownSection(s"*${evt.title}*"),
            hostServiceSection(evt.serviceParams),
            JuxtaposeSection(
              TextField("Took", fmt.format(evt.took)),
              TextField("Name", evt.digested.metricRepr)),
            MarkdownSection(s"""*Action ID:* ${evt.actionId.show}
                               |*Trace ID:* ${traceId(evt)}
                               |*Policy:* ${evt.actionParams.retryPolicy}
                               |*Service ID:* ${evt.serviceId.show}""".stripMargin),
            MarkdownSection(s"""```${abbrev(evt.error.message)} 
                               |Input: 
                               |${abbreviate(evt.actionInfo.input.spaces2)}```""".stripMargin)
          )
        )
      )
    )

  private def actionSucced(evt: ActionSucc): SlackApp =
    SlackApp(
      username = evt.serviceParams.taskParams.taskName.value,
      attachments = List(
        Attachment(
          color = coloring(evt),
          blocks = List(
            MarkdownSection(s"*${evt.title}*"),
            hostServiceSection(evt.serviceParams),
            JuxtaposeSection(
              TextField("Took", fmt.format(evt.took)),
              TextField("Name", evt.digested.metricRepr)),
            MarkdownSection(s"""*Action ID:* ${evt.actionId.show}
                               |*Trace ID:* ${traceId(evt)}
                               |*Service ID:* ${evt.serviceId.show}""".stripMargin),
            KeyValueSection("Output", s"""```${abbreviate(evt.output.spaces2)}```""")
          )
        )
      )
    )

  def apply[F[_]: Applicative]: Translator[F, SlackApp] =
    Translator
      .empty[F, SlackApp]
      .withServiceStart(serviceStarted)
      .withServicePanic(servicePanic)
      .withServiceStop(serviceStopped)
      .withMetricReport(metricReport)
      .withMetricReset(metricReset)
      .withInstantAlert(instantAlert)
      .withActionStart(actionStart)
      .withActionRetry(actionRetrying)
      .withActionFail(actionFailed)
      .withActionSucc(actionSucced)
}
