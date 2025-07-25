package com.github.chenharryhua.nanjin.guard.batch

import cats.effect.kernel.MonadCancel
import cats.implicits.catsSyntaxFlatMapOps
import cats.{Applicative, Monoid}
import com.github.chenharryhua.nanjin.guard.service.Agent
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}
import squants.Dimensionless
import squants.information.Information

sealed trait TraceJob[F[_], A] {
  private[batch] def kickoff(bj: BatchJob): F[Unit]
  private[batch] def canceled(bj: BatchJob): F[Unit]
  private[batch] def completed(jrv: JobResultValue[A]): F[Unit]
  private[batch] def errored(jre: JobResultError): F[Unit]
}

object TraceJob {
  sealed protected trait EventHandle[F[_], A] {
    def onComplete(f: JobResultValue[A] => F[Unit]): EventHandle[F, A]
    def onError(f: JobResultError => F[Unit]): EventHandle[F, A]
    def onCancel(f: BatchJob => F[Unit]): EventHandle[F, A]
    def onKickoff(f: BatchJob => F[Unit]): EventHandle[F, A]
  }

  final class JobTracer[F[_], A] private[TraceJob] (
    _completed: JobResultValue[A] => F[Unit],
    _errored: JobResultError => F[Unit],
    _canceled: BatchJob => F[Unit],
    _kickoff: BatchJob => F[Unit]
  ) extends TraceJob[F, A] with EventHandle[F, A] {
    override private[batch] def kickoff(bj: BatchJob): F[Unit] = _kickoff(bj)
    override private[batch] def canceled(bj: BatchJob): F[Unit] = _canceled(bj)
    override private[batch] def completed(jrv: JobResultValue[A]): F[Unit] = _completed(jrv)
    override private[batch] def errored(jre: JobResultError): F[Unit] = _errored(jre)

    private def copy(
      _completed: JobResultValue[A] => F[Unit] = this._completed,
      _errored: JobResultError => F[Unit] = this._errored,
      _canceled: BatchJob => F[Unit] = this._canceled,
      _kickoff: BatchJob => F[Unit] = this._kickoff): JobTracer[F, A] =
      new JobTracer[F, A](_completed, _errored, _canceled, _kickoff)

    override def onComplete(f: JobResultValue[A] => F[Unit]): JobTracer[F, A] = copy(_completed = f)
    override def onError(f: JobResultError => F[Unit]): JobTracer[F, A] = copy(_errored = f)
    override def onCancel(f: BatchJob => F[Unit]): JobTracer[F, A] = copy(_canceled = f)
    override def onKickoff(f: BatchJob => F[Unit]): JobTracer[F, A] = copy(_kickoff = f)

    def contramap[B](f: B => A): JobTracer[F, B] =
      new JobTracer[F, B](
        _completed = (jrv: JobResultValue[B]) => _completed(jrv.map(f)),
        _errored = this._errored,
        _canceled = this._canceled,
        _kickoff = this._kickoff
      )
  }

  def noop[F[_], A](implicit F: Applicative[F]): JobTracer[F, A] =
    new JobTracer[F, A](
      _completed = _ => F.unit,
      _errored = _ => F.unit,
      _canceled = _ => F.unit,
      _kickoff = _ => F.unit
    )

  final class ByAgent[F[_]] private[TraceJob] (
    private[TraceJob] val _agent: Agent[F],
    private[TraceJob] val _kickoff: Json => F[Unit],
    private[TraceJob] val _failure: Json => F[Unit],
    private[TraceJob] val _success: Json => F[Unit],
    private[TraceJob] val _canceled: Json => F[Unit],
    private[TraceJob] val _errored: JobResultError => F[Unit]) {
    private def copy(
      _kickoff: Json => F[Unit] = this._kickoff,
      _failure: Json => F[Unit] = this._failure,
      _success: Json => F[Unit] = this._success
    ): ByAgent[F] = new ByAgent[F](
      _agent = this._agent,
      _kickoff = _kickoff,
      _failure = _failure,
      _success = _success,
      _canceled = this._canceled,
      _errored = this._errored
    )

    object anchor {
      val warn: Json => F[Unit] = _agent.console.warn(_)
      val done: Json => F[Unit] = _agent.console.done(_)
      val info: Json => F[Unit] = _agent.console.info(_)
      val debug: Json => F[Unit] = _agent.console.debug(_)
      val void: Json => F[Unit] = _agent.console.void(_)
    }

    def routeKickoff(f: anchor.type => Json => F[Unit]): ByAgent[F] =
      copy(_kickoff = f(anchor))

    def routeSuccess(f: anchor.type => Json => F[Unit]): ByAgent[F] =
      copy(_success = f(anchor))

    def routeFailure(f: anchor.type => Json => F[Unit]): ByAgent[F] =
      copy(_failure = f(anchor))

    def universal[A](f: (A, JobResultState) => Json): JobTracer[F, A] =
      new JobTracer[F, A](
        _completed = { (jrv: JobResultValue[A]) =>
          val json: Json =
            Json.obj("outcome" -> f(jrv.value, jrv.resultState)).deepMerge(jrv.resultState.asJson)
          if (jrv.resultState.done) _success(json) else _failure(json)
        },
        _errored = (jre: JobResultError) => _errored(jre),
        _canceled = (bj: BatchJob) => _canceled(Json.obj("canceled" -> bj.asJson)),
        _kickoff = (bj: BatchJob) => _kickoff(Json.obj("kickoff" -> bj.asJson))
      )

    def standard[A: Encoder]: JobTracer[F, A] =
      universal[A]((a, _) => a.asJson)

    def json: JobTracer[F, Json] = standard[Json]

    def informationRate: JobTracer[F, Information] = {
      def translate(number: Information, jrs: JobResultState): Json =
        Json.obj("information" -> jsonDataRate(jrs.took, number))

      universal[Information](translate)
    }

    def dimensionlessRate: JobTracer[F, Dimensionless] = {
      def translate(number: Dimensionless, jrs: JobResultState): Json =
        Json.obj("dimensionless" -> jsonScalarRate(jrs.took, number))

      universal[Dimensionless](translate)
    }
  }

  def apply[F[_]](agent: Agent[F]): ByAgent[F] =
    new ByAgent[F](
      _agent = agent,
      _kickoff = agent.herald.info(_),
      _failure = agent.herald.warn(_),
      _success = agent.herald.done(_),
      _canceled = agent.console.warn(_),
      _errored = (jre: JobResultError) => agent.herald.error(jre.error)(jre.resultState)
    )

  implicit def monoidTraceJob[F[_], A](implicit ev: MonadCancel[F, Throwable]): Monoid[TraceJob[F, A]] =
    new Monoid[TraceJob[F, A]] {

      override val empty: TraceJob[F, A] = noop[F, A]

      override def combine(x: TraceJob[F, A], y: TraceJob[F, A]): TraceJob[F, A] =
        new TraceJob[F, A] {
          override private[batch] def kickoff(bj: BatchJob): F[Unit] =
            ev.uncancelable(_ => x.kickoff(bj) >> y.kickoff(bj))

          override private[batch] def canceled(bj: BatchJob): F[Unit] =
            ev.uncancelable(_ => x.canceled(bj) >> y.canceled(bj))

          override private[batch] def completed(jrv: JobResultValue[A]): F[Unit] =
            ev.uncancelable(_ => x.completed(jrv) >> y.completed(jrv))

          override private[batch] def errored(jre: JobResultError): F[Unit] =
            ev.uncancelable(_ => x.errored(jre) >> y.errored(jre))
        }
    }
}
