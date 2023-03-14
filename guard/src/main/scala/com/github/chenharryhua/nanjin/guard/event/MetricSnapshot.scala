package com.github.chenharryhua.nanjin.guard.event

import cats.Show
import cats.kernel.Monoid
import cats.syntax.all.*
import com.codahale.metrics.*
import com.github.chenharryhua.nanjin.guard.config.MeasurementID
import io.circe.Json
import io.circe.generic.JsonCodec
import io.circe.parser.{decode, parse}
import org.typelevel.cats.time.instances.duration
import squants.time.{Frequency, Hertz}

import java.time.Duration
import scala.jdk.CollectionConverters.*

@JsonCodec
sealed abstract private[guard] class MetricCategory(val value: String)

private[guard] object MetricCategory {

  case object ActionTimer extends MetricCategory("timer")
  case object ActionCompleteCounter extends MetricCategory("action.done")
  case object ActionFailCounter extends MetricCategory("action.fail")
  case object ActionRetryCounter extends MetricCategory("action.retries")

  case object Meter extends MetricCategory("meter")
  case object MeterCounter extends MetricCategory("meter.events")

  final case class Histogram(unitOfMeasure: String) extends MetricCategory("histogram")
  case object HistogramCounter extends MetricCategory("histogram.updates")

  case object Counter extends MetricCategory("count")

  case object Gauge extends MetricCategory("gauge")

  case object PassThroughCounter extends MetricCategory("passThrough")

  case object AlertErrorCounter extends MetricCategory("alert.error")
  case object AlertWarnCounter extends MetricCategory("alert.warn")
  case object AlertInfoCounter extends MetricCategory("alert.info")
}

@JsonCodec
final private[guard] case class MetricID(id: MeasurementID, category: MetricCategory)

sealed trait Snapshot {
  def id: MeasurementID
  final def isMatch(str: String): Boolean = id.name === str || id.digest === str
}

object Snapshot {

  @JsonCodec
  final case class Counter(id: MeasurementID, category: String, count: Long) extends Snapshot

  @JsonCodec
  final case class Meter(
    id: MeasurementID,
    count: Long,
    mean_rate: Frequency,
    m1_rate: Frequency,
    m5_rate: Frequency,
    m15_rate: Frequency
  ) extends Snapshot

  @JsonCodec
  final case class Timer(
    id: MeasurementID,
    count: Long,
    mean_rate: Frequency,
    m1_rate: Frequency,
    m5_rate: Frequency,
    m15_rate: Frequency,
    min: Duration,
    max: Duration,
    mean: Duration,
    stddev: Duration,
    p50: Duration,
    p75: Duration,
    p95: Duration,
    p98: Duration,
    p99: Duration,
    p999: Duration
  ) extends Snapshot

  @JsonCodec
  final case class Histogram(
    id: MeasurementID,
    unit: String,
    count: Long,
    min: Long,
    max: Long,
    mean: Double,
    stddev: Double,
    p50: Double,
    p75: Double,
    p95: Double,
    p98: Double,
    p99: Double,
    p999: Double)
      extends Snapshot

  @JsonCodec
  final case class Gauge(id: MeasurementID, value: Json) extends Snapshot
}

@JsonCodec
final case class MetricSnapshot(
  gauges: List[Snapshot.Gauge], // important measurement comes first.
  counters: List[Snapshot.Counter],
  meters: List[Snapshot.Meter],
  timers: List[Snapshot.Timer],
  histograms: List[Snapshot.Histogram])

object MetricSnapshot extends duration {

  implicit val showMetricSnapshot: Show[MetricSnapshot] = cats.derived.semiauto.show[MetricSnapshot]

  implicit val monoidMetricFilter: Monoid[MetricFilter] = new Monoid[MetricFilter] {
    override val empty: MetricFilter = MetricFilter.ALL

    override def combine(x: MetricFilter, y: MetricFilter): MetricFilter =
      (name: String, metric: Metric) => x.matches(name, metric) && y.matches(name, metric)
  }

  def counters(metricRegistry: MetricRegistry): List[Snapshot.Counter] =
    metricRegistry.getCounters().asScala.toList.mapFilter { case (name, counter) =>
      decode[MetricID](name).toOption.map(mn => Snapshot.Counter(mn.id, mn.category.value, counter.getCount))
    }

  def meters(metricRegistry: MetricRegistry): List[Snapshot.Meter] =
    metricRegistry.getMeters().asScala.toList.mapFilter { case (name, meter) =>
      decode[MetricID](name).toOption.map(mn =>
        Snapshot.Meter(
          id = mn.id,
          count = meter.getCount,
          mean_rate = Hertz(meter.getMeanRate),
          m1_rate = Hertz(meter.getOneMinuteRate),
          m5_rate = Hertz(meter.getFiveMinuteRate),
          m15_rate = Hertz(meter.getFifteenMinuteRate)
        ))
    }

  def timers(metricRegistry: MetricRegistry): List[Snapshot.Timer] =
    metricRegistry.getTimers().asScala.toList.mapFilter { case (name, timer) =>
      decode[MetricID](name).toOption.map { mn =>
        val ss = timer.getSnapshot
        Snapshot.Timer(
          id = mn.id,
          count = timer.getCount,
          // meter
          mean_rate = Hertz(timer.getMeanRate),
          m1_rate = Hertz(timer.getOneMinuteRate),
          m5_rate = Hertz(timer.getFiveMinuteRate),
          m15_rate = Hertz(timer.getFifteenMinuteRate),
          // histogram
          min = Duration.ofNanos(ss.getMin),
          max = Duration.ofNanos(ss.getMax),
          mean = Duration.ofNanos(ss.getMean.toLong),
          stddev = Duration.ofNanos(ss.getStdDev.toLong),
          p50 = Duration.ofNanos(ss.getMedian.toLong),
          p75 = Duration.ofNanos(ss.get75thPercentile().toLong),
          p95 = Duration.ofNanos(ss.get95thPercentile().toLong),
          p98 = Duration.ofNanos(ss.get98thPercentile().toLong),
          p99 = Duration.ofNanos(ss.get99thPercentile().toLong),
          p999 = Duration.ofNanos(ss.get999thPercentile().toLong)
        )
      }
    }

  def histograms(metricRegistry: MetricRegistry): List[Snapshot.Histogram] =
    metricRegistry.getHistograms().asScala.toList.mapFilter { case (name, histo) =>
      decode[MetricID](name).toOption.flatMap { mn =>
        mn.category match {
          case MetricCategory.Histogram(unitOfMeasure) =>
            val ss = histo.getSnapshot
            Some(
              Snapshot.Histogram(
                id = mn.id,
                unit = unitOfMeasure,
                count = histo.getCount,
                min = ss.getMin,
                max = ss.getMax,
                mean = ss.getMean,
                stddev = ss.getStdDev,
                p50 = ss.getMedian,
                p75 = ss.get75thPercentile(),
                p95 = ss.get95thPercentile(),
                p98 = ss.get98thPercentile(),
                p99 = ss.get99thPercentile(),
                p999 = ss.get999thPercentile()
              ))
          case _ => None
        }
      }
    }

  def gauges(metricRegistry: MetricRegistry): List[Snapshot.Gauge] =
    metricRegistry.getGauges().asScala.toList.mapFilter { case (name, gauge) =>
      (decode[MetricID](name), parse(gauge.getValue.toString))
        .mapN((id, json) => Snapshot.Gauge(id.id, json))
        .toOption
    }

  def apply(metricRegistry: MetricRegistry): MetricSnapshot =
    MetricSnapshot(
      gauges = gauges(metricRegistry).sortBy(_.id.name),
      counters = counters(metricRegistry).sortBy(_.id.name),
      meters = meters(metricRegistry).sortBy(_.id.name),
      timers = timers(metricRegistry).sortBy(_.id.name),
      histograms = histograms(metricRegistry).sortBy(_.id.name)
    )
}
