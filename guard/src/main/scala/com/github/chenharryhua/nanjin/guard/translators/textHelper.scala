package com.github.chenharryhua.nanjin.guard.translators

import cats.implicits.toShow
import com.github.chenharryhua.nanjin.guard.config.{MetricName, MetricParams}
import com.github.chenharryhua.nanjin.guard.event.NJEvent.{ActionRetry, ServicePanic}
import com.github.chenharryhua.nanjin.guard.event.{MetricIndex, MetricSnapshot, NJEvent}
import org.typelevel.cats.time.instances.{localdatetime, localtime}

import java.time.temporal.ChronoUnit
import java.time.{Duration, ZonedDateTime}

object textConstants {
  @inline final val CONSTANT_ACTION_ID: String   = "ActionID"
  @inline final val CONSTANT_TRACE_ID: String    = "TraceID"
  @inline final val CONSTANT_TIMESTAMP: String   = "Timestamp"
  @inline final val CONSTANT_POLICY: String      = "Policy"
  @inline final val CONSTANT_CAUSE: String       = "Cause"
  @inline final val CONSTANT_TOOK: String        = "Took"
  @inline final val CONSTANT_DELAYED: String     = "Delayed"
  @inline final val CONSTANT_UPTIME: String      = "UpTime"
  @inline final val CONSTANT_BRIEF: String       = "Brief"
  @inline final val CONSTANT_METRICS: String     = "Metrics"
  @inline final val CONSTANT_TIMEZONE: String    = "TimeZone"
  @inline final val CONSTANT_SERVICE: String     = "Service"
  @inline final val CONSTANT_SERVICE_ID: String  = "ServiceID"
  @inline final val CONSTANT_HOST: String        = "Host"
  @inline final val CONSTANT_TASK: String        = "Task"
  @inline final val CONSTANT_IMPORTANCE: String  = "Importance"
  @inline final val CONSTANT_STRATEGY: String    = "Strategy"
  @inline final val CONSTANT_MEASUREMENT: String = "Measurement"
}

private object textHelper extends localtime with localdatetime {
  def yamlMetrics(ss: MetricSnapshot, mp: MetricParams): String =
    new SnapshotPolyglot(ss, mp).toYaml

  def upTimeText(evt: NJEvent): String     = fmt.format(evt.upTime)
  def tookText(duration: Duration): String = fmt.format(duration)

  def eventTitle(evt: NJEvent): String = {
    def name(mn: MetricName): String = s"[${mn.digest}][${mn.value}]"

    evt match {
      case NJEvent.ActionStart(ap, _, _)          => s"Start Action ${name(ap.metricId.metricName)}"
      case NJEvent.ActionRetry(ap, _, _, _, _, _) => s"Action Retrying ${name(ap.metricId.metricName)}"
      case NJEvent.ActionFail(ap, _, _, _, _)     => s"Action Failed ${name(ap.metricId.metricName)}"
      case NJEvent.ActionComplete(ap, _, _, _)    => s"Action Completed ${name(ap.metricId.metricName)}"

      case NJEvent.ServiceAlert(metricName, _, _, al, _) => s"Alert ${al.productPrefix} ${name(metricName)}"

      case _: NJEvent.ServiceStart => "(Re)Start Service"
      case _: NJEvent.ServiceStop  => "Service Stopped"
      case _: NJEvent.ServicePanic => "Service Panic"

      case NJEvent.MetricReport(index, _, _, _) =>
        index match {
          case MetricIndex.Adhoc         => "Adhoc Metric Report"
          case MetricIndex.Periodic(idx) => s"Metric Report(index=$idx)"
        }
      case NJEvent.MetricReset(index, _, _, _) =>
        index match {
          case MetricIndex.Adhoc         => "Adhoc Metric Reset"
          case MetricIndex.Periodic(idx) => s"Metric Reset(index=$idx)"
        }
    }
  }

  private def toOrdinalWords(n: Int): String = {
    val w =
      if (n % 100 / 10 == 1) "th"
      else {
        n % 10 match {
          case 1 => "st"
          case 2 => "nd"
          case 3 => "rd"
          case _ => "th"
        }
      }
    s"$n$w"
  }

  private def localTimeAndDurationStr(start: ZonedDateTime, end: ZonedDateTime): (String, String) = {
    val duration = Duration.between(start, end)
    val localTime: String =
      if (duration.minus(Duration.ofHours(24)).isNegative)
        end.truncatedTo(ChronoUnit.SECONDS).toLocalTime.show
      else
        end.truncatedTo(ChronoUnit.SECONDS).toLocalDateTime.show

    (localTime, fmt.format(duration))
  }

  def panicText(evt: ServicePanic): String = {
    val (time, dur) = localTimeAndDurationStr(evt.timestamp, evt.restartTime)
    s"The service experienced a panic. Restart was scheduled at *$time*, roughly in $dur."
  }

  def retryText(evt: ActionRetry): String = {
    val resumeTime = evt.timestamp.plusNanos(evt.delay.toNanos)
    val next       = fmt.format(Duration.between(evt.timestamp, resumeTime))
    val localTs    = resumeTime.toLocalTime.truncatedTo(ChronoUnit.SECONDS)
    s"*${toOrdinalWords(evt.retriesSoFar + 1)}* retry at $localTs, in $next"
  }
}