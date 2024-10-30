package com.github.chenharryhua.nanjin.guard.metrics

import cats.Endo
import cats.data.Kleisli
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import com.codahale.metrics.MetricRegistry
import com.github.chenharryhua.nanjin.common.DurationFormatter
import com.github.chenharryhua.nanjin.guard.config.{MetricName, MetricTag}
import com.github.chenharryhua.nanjin.guard.event.NJUnits

import scala.concurrent.duration.DurationInt

sealed trait NJMetrics[F[_]] {
  def metricName: MetricName

  def counter(tag: String, f: Endo[NJCounter.Builder]): Resource[F, NJCounter[F]]
  final def counter(tag: String): Resource[F, NJCounter[F]] = counter(tag, identity)

  def meter(tag: String, f: Endo[NJMeter.Builder]): Resource[F, NJMeter[F]]
  final def meter(tag: String): Resource[F, NJMeter[F]] = meter(tag, identity)

  def histogram(tag: String, f: Endo[NJHistogram.Builder]): Resource[F, NJHistogram[F]]
  final def histogram(tag: String): Resource[F, NJHistogram[F]] =
    histogram(tag, identity)

  def timer(tag: String, f: Endo[NJTimer.Builder]): Resource[F, NJTimer[F]]
  final def timer(tag: String): Resource[F, NJTimer[F]] = timer(tag, identity)

  def ratio(tag: String, f: Endo[NJRatio.Builder]): Resource[F, NJRatio[F]]
  final def ratio(tag: String): Resource[F, NJRatio[F]] = ratio(tag, identity)

  def healthCheck(tag: String, f: Endo[NJHealthCheck.Builder]): NJHealthCheck[F]
  final def healthCheck(tag: String): NJHealthCheck[F] = healthCheck(tag, identity)

  // gauge
  def gauge(tag: String, f: Endo[NJGauge.Builder]): NJGauge[F]
  final def gauge(tag: String): NJGauge[F] = gauge(tag, identity)

  def idleGauge(tag: String, f: Endo[NJGauge.Builder]): Resource[F, Kleisli[F, Unit, Unit]]
  final def idleGauge(tag: String): Resource[F, Kleisli[F, Unit, Unit]] =
    idleGauge(tag, identity)

  def activeGauge(tag: String, f: Endo[NJGauge.Builder]): Resource[F, Unit]
  final def activeGauge(tag: String): Resource[F, Unit] = activeGauge(tag, identity)

}

object NJMetrics {
  private[guard] class Impl[F[_]](
    val metricName: MetricName,
    metricRegistry: MetricRegistry,
    isEnabled: Boolean)(implicit F: Async[F])
      extends NJMetrics[F] {

    override def counter(tag: String, f: Endo[NJCounter.Builder]): Resource[F, NJCounter[F]] = {
      val init = new NJCounter.Builder(isEnabled, false)
      f(init).build[F](metricName, MetricTag(tag), metricRegistry)
    }

    override def meter(tag: String, f: Endo[NJMeter.Builder]): Resource[F, NJMeter[F]] = {
      val init = new NJMeter.Builder(isEnabled, NJUnits.COUNT)
      f(init).build[F](metricName, MetricTag(tag), metricRegistry)
    }

    override def histogram(tag: String, f: Endo[NJHistogram.Builder]): Resource[F, NJHistogram[F]] = {
      val init = new NJHistogram.Builder(isEnabled, NJUnits.COUNT, None)
      f(init).build[F](metricName, MetricTag(tag), metricRegistry)
    }

    override def timer(tag: String, f: Endo[NJTimer.Builder]): Resource[F, NJTimer[F]] = {
      val init = new NJTimer.Builder(isEnabled, None)
      f(init).build[F](metricName, MetricTag(tag), metricRegistry)
    }

    override def healthCheck(tag: String, f: Endo[NJHealthCheck.Builder]): NJHealthCheck[F] = {
      val init = new NJHealthCheck.Builder(isEnabled, timeout = 5.seconds)
      f(init).build[F](metricName, tag, metricRegistry)
    }

    override def ratio(tag: String, f: Endo[NJRatio.Builder]): Resource[F, NJRatio[F]] = {
      val init = new NJRatio.Builder(isEnabled, NJRatio.translator)
      f(init).build[F](metricName, MetricTag(tag), metricRegistry)
    }

    override def gauge(tag: String, f: Endo[NJGauge.Builder]): NJGauge[F] = {
      val init = new NJGauge.Builder(isEnabled, 5.seconds)
      f(init).build[F](metricName, MetricTag(tag), metricRegistry)
    }

    override def idleGauge(tag: String, f: Endo[NJGauge.Builder]): Resource[F, Kleisli[F, Unit, Unit]] =
      for {
        lastUpdate <- Resource.eval(F.monotonic.flatMap(F.ref))
        _ <- gauge(tag, f).register(
          for {
            pre <- lastUpdate.get
            now <- F.monotonic
          } yield DurationFormatter.defaultFormatter.format(now - pre)
        )
      } yield Kleisli[F, Unit, Unit](_ => F.monotonic.flatMap(lastUpdate.set))

    override def activeGauge(tag: String, f: Endo[NJGauge.Builder]): Resource[F, Unit] =
      for {
        kickoff <- Resource.eval(F.monotonic)
        _ <- gauge(tag, f).register(F.monotonic.map(now =>
          DurationFormatter.defaultFormatter.format(now - kickoff)))
      } yield ()
  }
}