package com.github.chenharryhua.nanjin.guard.action
import cats.Endo
import cats.data.Ior
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import com.codahale.metrics.MetricRegistry
import com.github.chenharryhua.nanjin.guard.config.ServiceParams
import com.github.chenharryhua.nanjin.guard.translator.fmt
import io.circe.Json
import io.circe.syntax.EncoderOps

import java.time.Duration
import scala.jdk.DurationConverters.ScalaDurationOps

object BatchRunner {
  sealed abstract class Runner[F[_]: Async, A](
    serviceParams: ServiceParams,
    metricRegistry: MetricRegistry,
    action: NJAction[F],
    ratioBuilder: NJRatio.Builder,
    gaugeBuilder: NJGauge.Builder
  ) {
    protected[this] val F: Async[F] = Async[F]

    protected[this] val nullTransform: A => Json = _ => Json.Null

    protected[this] def tap(f: A => Json): Endo[BuildWith.Builder[F, (BatchJob, F[A]), A]] =
      _.tapInput { case (job, _) =>
        job.asJson
      }.tapOutput { case ((job, _), out) =>
        job.asJson.deepMerge(Json.obj("result" -> f(out)))
      }.tapError { case ((job, _), _) =>
        job.asJson
      }

    private[this] val translator: Ior[Long, Long] => Json = {
      case Ior.Left(a)  => Json.fromString(s"$a/0")
      case Ior.Right(b) => Json.fromString(s"0/$b")
      case Ior.Both(a, b) =>
        val expression = s"$a/$b"
        if (b === 0) { Json.fromString(expression) }
        else {
          val rounded: Float =
            BigDecimal(a * 100.0 / b).setScale(2, BigDecimal.RoundingMode.HALF_UP).toFloat
          Json.fromString(s"$rounded% ($expression)")
        }
    }

    protected[this] val ratio: Resource[F, NJRatio[F]] =
      for {
        kickoff <- Resource.eval(F.monotonic)
        _ <- gaugeBuilder
          .enable(action.actionParams.isEnabled)
          .withMeasurement(action.actionParams.measurement.value)
          .withTag("elapsed")
          .build[F](action.actionParams.actionName.value, metricRegistry, serviceParams)
          .register(F.monotonic.map(now => Json.fromString(fmt.format(now - kickoff))))
        rat <- ratioBuilder
          .enable(action.actionParams.isEnabled)
          .withMeasurement(action.actionParams.measurement.value)
          .withTranslator(translator)
          .withTag("ratio")
          .build(action.actionParams.actionName.value, metricRegistry, serviceParams)
      } yield rat
  }

  final class Parallel[F[_]: Async, A] private[action] (
    serviceParams: ServiceParams,
    metricRegistry: MetricRegistry,
    action: NJAction[F],
    ratioBuilder: NJRatio.Builder,
    gaugeBuilder: NJGauge.Builder,
    parallelism: Int,
    jobs: List[(Option[BatchJobName], F[A])])
      extends Runner[F, A](serviceParams, metricRegistry, action, ratioBuilder, gaugeBuilder) {

    def quasi(f: A => Json): F[QuasiResult] = {
      val batchJobs: List[(BatchJob, F[A])] = jobs.zipWithIndex.map { case ((name, fa), idx) =>
        BatchJob(BatchKind.Quasi, BatchMode.Parallel(parallelism), name, idx, jobs.size) -> fa
      }
      val exec: Resource[F, F[QuasiResult]] = for {
        rat <- ratio.evalTap(_.incDenominator(batchJobs.size.toLong))
        act <- action.retry((_: BatchJob, fa: F[A]) => fa).buildWith(tap(f))
      } yield F
        .timed(F.parTraverseN(parallelism)(batchJobs) { case (job, fa) =>
          F.timed(act.run((job, fa)).attempt)
            .map { case (fd, result) => Detail(job, fd.toJava, result.isRight) }
            .flatTap(_ => rat.incNumerator(1))
        })
        .map { case (fd, details) =>
          QuasiResult(
            name = action.actionParams.actionName,
            spent = fd.toJava,
            mode = BatchMode.Parallel(parallelism),
            details = details.sortBy(_.job.index))
        }

      exec.use(identity)
    }

    val quasi: F[QuasiResult] = quasi(nullTransform)

    def run(f: A => Json): F[List[A]] = {
      val batchJobs: List[(BatchJob, F[A])] = jobs.zipWithIndex.map { case ((name, fa), idx) =>
        BatchJob(BatchKind.Batch, BatchMode.Parallel(parallelism), name, idx, jobs.size) -> fa
      }

      val exec: Resource[F, F[List[A]]] = for {
        rat <- ratio.evalTap(_.incDenominator(batchJobs.size.toLong))
        act <- action.retry((_: BatchJob, fa: F[A]) => fa).buildWith(tap(f))
      } yield F.parTraverseN(parallelism)(batchJobs) { case (job, fa) =>
        act.run((job, fa)).flatTap(_ => rat.incNumerator(1))
      }

      exec.use(identity)
    }

    val run: F[List[A]] = run(nullTransform)
  }

  final class Sequential[F[_]: Async, A] private[action] (
    serviceParams: ServiceParams,
    metricRegistry: MetricRegistry,
    action: NJAction[F],
    ratioBuilder: NJRatio.Builder,
    gaugeBuilder: NJGauge.Builder,
    jobs: List[(Option[BatchJobName], F[A])])
      extends Runner[F, A](serviceParams, metricRegistry, action, ratioBuilder, gaugeBuilder) {

    def quasi(f: A => Json): F[QuasiResult] = {
      val batchJobs: List[(BatchJob, F[A])] = jobs.zipWithIndex.map { case ((name, fa), idx) =>
        BatchJob(BatchKind.Quasi, BatchMode.Sequential, name, idx, jobs.size) -> fa
      }

      val exec: Resource[F, F[QuasiResult]] = for {
        rat <- ratio.evalTap(_.incDenominator(batchJobs.size.toLong))
        act <- action.retry((_: BatchJob, fa: F[A]) => fa).buildWith(tap(f))
      } yield batchJobs.traverse { case (job, fa) =>
        F.timed(act.run((job, fa)).attempt)
          .map { case (fd, result) => Detail(job, fd.toJava, result.isRight) }
          .flatTap(_ => rat.incNumerator(1))
      }.map(details =>
        QuasiResult(
          name = action.actionParams.actionName,
          spent = details.map(_.took).foldLeft(Duration.ZERO)(_ plus _),
          mode = BatchMode.Sequential,
          details = details.sortBy(_.job.index)
        ))

      exec.use(identity)
    }

    val quasi: F[QuasiResult] = quasi(nullTransform)

    def run(f: A => Json): F[List[A]] = {
      val batchJobs: List[(BatchJob, F[A])] = jobs.zipWithIndex.map { case ((name, fa), idx) =>
        BatchJob(BatchKind.Batch, BatchMode.Sequential, name, idx, jobs.size) -> fa
      }

      val exec: Resource[F, F[List[A]]] = for {
        rat <- ratio.evalTap(_.incDenominator(batchJobs.size.toLong))
        act <- action.retry((_: BatchJob, fa: F[A]) => fa).buildWith(tap(f))
      } yield batchJobs.traverse { case (job, fa) =>
        act.run((job, fa)).flatTap(_ => rat.incNumerator(1))
      }

      exec.use(identity)
    }

    val run: F[List[A]] = run(nullTransform)
  }
}

final class NJBatch[F[_]: Async] private[guard] (
  serviceParams: ServiceParams,
  metricRegistry: MetricRegistry,
  action: NJAction[F],
  ratioBuilder: NJRatio.Builder,
  gaugeBuilder: NJGauge.Builder
) {
  def sequential[A](fas: F[A]*): BatchRunner.Sequential[F, A] = {
    val jobs: List[(Option[BatchJobName], F[A])] = fas.toList.map(none -> _)
    new BatchRunner.Sequential[F, A](serviceParams, metricRegistry, action, ratioBuilder, gaugeBuilder, jobs)
  }

  def namedSequential[A](fas: (String, F[A])*): BatchRunner.Sequential[F, A] = {
    val jobs: List[(Option[BatchJobName], F[A])] = fas.toList.map { case (name, fa) =>
      BatchJobName(name).some -> fa
    }
    new BatchRunner.Sequential[F, A](serviceParams, metricRegistry, action, ratioBuilder, gaugeBuilder, jobs)
  }

  def parallel[A](parallelism: Int)(fas: F[A]*): BatchRunner.Parallel[F, A] = {
    val jobs: List[(Option[BatchJobName], F[A])] = fas.toList.map(none -> _)
    new BatchRunner.Parallel[F, A](
      serviceParams,
      metricRegistry,
      action,
      ratioBuilder,
      gaugeBuilder,
      parallelism,
      jobs)
  }

  def parallel[A](fas: F[A]*): BatchRunner.Parallel[F, A] =
    parallel[A](fas.size)(fas*)

  def namedParallel[A](parallelism: Int)(fas: (String, F[A])*): BatchRunner.Parallel[F, A] = {
    val jobs: List[(Option[BatchJobName], F[A])] = fas.toList.map { case (name, fa) =>
      BatchJobName(name).some -> fa
    }
    new BatchRunner.Parallel[F, A](
      serviceParams,
      metricRegistry,
      action,
      ratioBuilder,
      gaugeBuilder,
      parallelism,
      jobs)
  }

  def namedParallel[A](fas: (String, F[A])*): BatchRunner.Parallel[F, A] =
    namedParallel[A](fas.size)(fas*)
}
