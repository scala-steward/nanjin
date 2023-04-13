package com.github.chenharryhua.nanjin.guard.event

import cats.Show
import cats.kernel.Monoid
import cats.syntax.all.*
import com.codahale.metrics.*
import com.github.chenharryhua.nanjin.guard.config.{
  showStandardUnit,
  standardUnitDecoder,
  standardUnitEncoder,
  Category,
  MetricID
}
import io.circe.Json
import io.circe.generic.JsonCodec
import io.circe.parser.{decode, parse}
import org.typelevel.cats.time.instances.duration
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit
import squants.time.{Frequency, Hertz}

import java.time.Duration
import scala.jdk.CollectionConverters.*

sealed trait Snapshot { def metricId: MetricID }

object Snapshot {

  @JsonCodec
  final case class Counter(metricId: MetricID, count: Long) extends Snapshot

  @JsonCodec
  final case class Gauge(metricId: MetricID, value: Json) extends Snapshot

  @JsonCodec
  final case class MeterData(
    unit: StandardUnit,
    count: Long,
    mean_rate: Frequency,
    m1_rate: Frequency,
    m5_rate: Frequency,
    m15_rate: Frequency
  ) {
    val unitShow: String = unit.show
  }
  @JsonCodec
  final case class Meter(metricId: MetricID, meter: MeterData) extends Snapshot

  @JsonCodec
  final case class TimerData(
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
  )
  @JsonCodec
  final case class Timer(metricId: MetricID, timer: TimerData) extends Snapshot

  @JsonCodec
  final case class HistogramData(
    unit: StandardUnit,
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
    p999: Double
  ) {
    val unitShow: String = unit.show
  }

  @JsonCodec
  final case class Histogram(metricId: MetricID, histogram: HistogramData) extends Snapshot
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
      decode[MetricID](name).toOption.map(id => Snapshot.Counter(id, counter.getCount))
    }

  def meters(metricRegistry: MetricRegistry): List[Snapshot.Meter] =
    metricRegistry.getMeters().asScala.toList.mapFilter { case (name, meter) =>
      decode[MetricID](name).toOption.mapFilter(id =>
        id.category match {
          case Category.Meter(unit, _) =>
            Some(
              Snapshot.Meter(
                metricId = id,
                Snapshot.MeterData(
                  unit = unit,
                  count = meter.getCount,
                  mean_rate = Hertz(meter.getMeanRate),
                  m1_rate = Hertz(meter.getOneMinuteRate),
                  m5_rate = Hertz(meter.getFiveMinuteRate),
                  m15_rate = Hertz(meter.getFifteenMinuteRate)
                )
              ))
          case _ => None
        })
    }

  def timers(metricRegistry: MetricRegistry): List[Snapshot.Timer] =
    metricRegistry.getTimers().asScala.toList.mapFilter { case (name, timer) =>
      decode[MetricID](name).toOption.map { id =>
        val ss = timer.getSnapshot
        Snapshot.Timer(
          metricId = id,
          Snapshot.TimerData(
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
        )
      }
    }

  def histograms(metricRegistry: MetricRegistry): List[Snapshot.Histogram] =
    metricRegistry.getHistograms().asScala.toList.mapFilter { case (name, histo) =>
      decode[MetricID](name).toOption.flatMap { id =>
        id.category match {
          case Category.Histogram(unit, _) =>
            val ss = histo.getSnapshot
            Some(
              Snapshot.Histogram(
                metricId = id,
                Snapshot.HistogramData(
                  unit = unit,
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
                )
              ))
          case _ => None
        }
      }
    }

  def gauges(metricRegistry: MetricRegistry): List[Snapshot.Gauge] =
    metricRegistry.getGauges().asScala.toList.mapFilter { case (name, gauge) =>
      (decode[MetricID](name), parse(gauge.getValue.toString))
        .mapN((id, json) => Snapshot.Gauge(id, json))
        .toOption
    }

  def apply(metricRegistry: MetricRegistry): MetricSnapshot =
    MetricSnapshot(
      gauges = gauges(metricRegistry).sortBy(_.metricId.metricName.value),
      counters = counters(metricRegistry).sortBy(_.metricId.metricName.value),
      meters = meters(metricRegistry).sortBy(_.metricId.metricName.value),
      timers = timers(metricRegistry).sortBy(_.metricId.metricName.value),
      histograms = histograms(metricRegistry).sortBy(_.metricId.metricName.value)
    )
}
