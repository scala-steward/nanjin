package com.github.chenharryhua.nanjin.guard.action
import cats.Show
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.guard.translator.fmt
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}

import java.time.Duration

sealed trait BatchKind
object BatchKind {
  case object Quasi extends BatchKind
  case object Batch extends BatchKind
  implicit val showBatchKind: Show[BatchKind] = {
    case Quasi => "quasi"
    case Batch => "batch"
  }
}

sealed trait BatchMode
object BatchMode {
  final case class Parallel(parallelism: Int) extends BatchMode
  case object Sequential extends BatchMode

  implicit val showBatchMode: Show[BatchMode] = {
    case Parallel(parallelism) => s"parallel-$parallelism"
    case Sequential            => "sequential"
  }

  implicit val encoderBatchMode: Encoder[BatchMode] =
    (a: BatchMode) => Json.fromString(a.show)
}

final case class BatchJobName(value: String) extends AnyVal
object BatchJobName {
  implicit val showBatchJobName: Show[BatchJobName]       = _.value
  implicit val encoderBatchJobName: Encoder[BatchJobName] = Encoder.encodeString.contramap(_.value)
}

final case class BatchJob(kind: BatchKind, mode: BatchMode, name: Option[BatchJobName], index: Int, jobs: Int)
object BatchJob {
  implicit val encoderBatchJob: Encoder[BatchJob] = (a: BatchJob) =>
    Json.obj(
      show"${a.kind}" -> Json
        .obj(
          "name" -> a.name.asJson,
          "mode" -> a.mode.asJson,
          "index" -> (a.index + 1).asJson,
          "jobs" -> a.jobs.asJson)
        .dropNullValues)
}

final case class Detail(job: BatchJob, took: Duration, done: Boolean)
final case class QuasiResult(spent: Duration, mode: BatchMode, details: List[Detail])
object QuasiResult {
  implicit val encoderQuasiResult: Encoder[QuasiResult] = { (a: QuasiResult) =>
    val (done, fail) = a.details.partition(_.done)
    Json.obj(
      "mode" -> a.mode.asJson,
      "spent" -> fmt.format(a.spent).asJson,
      "done" -> done.length.asJson,
      "fail" -> fail.length.asJson,
      "details" -> a.details
        .map(d =>
          Json
            .obj(
              "name" -> d.job.name.asJson,
              "index" -> (d.job.index + 1).asJson,
              "took" -> fmt.format(d.took).asJson,
              "done" -> d.done.asJson)
            .dropNullValues)
        .asJson
    )
  }
}
