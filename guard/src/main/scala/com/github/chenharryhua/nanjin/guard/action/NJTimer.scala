package com.github.chenharryhua.nanjin.guard.action

import cats.Applicative
import cats.data.Kleisli
import cats.effect.implicits.clockOps
import cats.effect.kernel.{Resource, Sync, Unique}
import cats.implicits.{toFlatMapOps, toFunctorOps}
import com.codahale.metrics.*
import com.github.chenharryhua.nanjin.guard.config.*
import com.github.chenharryhua.nanjin.guard.config.CategoryKind.TimerKind

import java.time.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps

sealed trait NJTimer[F[_]] {
  def update(fd: FiniteDuration): F[Unit]
  def unsafeUpdate(fd: FiniteDuration): Unit

  def timing[A](fa: F[A]): F[A]

  final def kleisli[A](f: A => FiniteDuration): Kleisli[F, A, Unit] =
    Kleisli(update).local(f)
}

private class NJTimerImpl[F[_]: Sync](
  private[this] val token: Unique.Token,
  private[this] val name: MetricName,
  private[this] val metricRegistry: MetricRegistry,
  private[this] val reservoir: Option[Reservoir],
  private[this] val tag: MetricTag
) extends NJTimer[F] {

  private[this] val F = Sync[F]

  private[this] val timer_name: String =
    MetricID(name, Category.Timer(TimerKind.Timer, tag), token).identifier

  private[this] val supplier: MetricRegistry.MetricSupplier[Timer] = () =>
    reservoir match {
      case Some(value) => new Timer(value)
      case None        => new Timer(new ExponentiallyDecayingReservoir) // default reservoir
    }

  private[this] lazy val timer: Timer = metricRegistry.timer(timer_name, supplier)

  private[this] val calculate: Duration => Unit =
    (duration: Duration) => timer.update(duration)

  override def timing[A](fa: F[A]): F[A] =
    fa.timed.flatMap { case (fd, a) => update(fd).as(a) }

  override def update(fd: FiniteDuration): F[Unit] = F.delay(calculate(fd.toJava))

  override def unsafeUpdate(fd: FiniteDuration): Unit = calculate(fd.toJava)

  val unregister: F[Unit] = F.delay {
    metricRegistry.remove(timer_name)
  }.void
}

object NJTimer {
  def dummy[F[_]](implicit F: Applicative[F]): NJTimer[F] =
    new NJTimer[F] {
      override def update(fd: FiniteDuration): F[Unit]    = F.unit
      override def unsafeUpdate(fd: FiniteDuration): Unit = ()

      override def timing[A](fa: F[A]): F[A] = fa
    }

  final class Builder private[guard] (
    measurement: Measurement,
    reservoir: Option[Reservoir],
    isEnabled: Boolean,
    tag: MetricTag)
      extends MetricBuilder[Builder] {

    def withReservoir(reservoir: Reservoir): Builder =
      new Builder(measurement, Some(reservoir), isEnabled, tag)

    override def withMeasurement(measurement: String): Builder =
      new Builder(Measurement(measurement), reservoir, isEnabled, tag)

    override def enable(value: Boolean): Builder =
      new Builder(measurement, reservoir, value, tag)

    override def withTag(tag: String): Builder =
      new Builder(measurement, reservoir, isEnabled, MetricTag(tag))

    private[guard] def build[F[_]](
      name: String,
      metricRegistry: MetricRegistry,
      serviceParams: ServiceParams)(implicit F: Sync[F]): Resource[F, NJTimer[F]] =
      if (isEnabled) {
        val metricName = MetricName(serviceParams, measurement, name)
        Resource.make(
          F.unique.map(token =>
            new NJTimerImpl[F](
              token = token,
              name = metricName,
              metricRegistry = metricRegistry,
              reservoir = reservoir,
              tag = tag)))(_.unregister)
      } else
        Resource.pure(dummy[F])
  }
}
