package com.github.chenharryhua.nanjin.spark.sstream

import java.util.concurrent.TimeUnit

import com.github.chenharryhua.nanjin.common.NJFileFormat
import com.github.chenharryhua.nanjin.datetime.NJDateTimeRange
import com.github.chenharryhua.nanjin.spark.{NJCheckpoint, NJFailOnDataLoss, NJShowDataset}
import higherkindness.droste.data.Fix
import higherkindness.droste.macros.deriveTraverse
import higherkindness.droste.{scheme, Algebra}
import monocle.macros.Lenses
import org.apache.spark.sql.streaming.{OutputMode, Trigger}

@Lenses final private[sstream] case class SStreamParams private (
  timeRange: NJDateTimeRange,
  showDs: NJShowDataset,
  fileFormat: NJFileFormat,
  checkpoint: NJCheckpoint,
  dataLoss: NJFailOnDataLoss,
  outputMode: OutputMode,
  trigger: Trigger)

private[sstream] object SStreamParams {

  def apply(tr: NJDateTimeRange, sd: NJShowDataset): SStreamParams =
    SStreamParams(
      timeRange = tr,
      showDs = sd,
      fileFormat = NJFileFormat.Jackson,
      checkpoint = NJCheckpoint("./data/checkpoint/sstream"),
      dataLoss = NJFailOnDataLoss(true),
      outputMode = OutputMode.Append,
      trigger = Trigger.ProcessingTime(1, TimeUnit.MINUTES)
    )
}

@deriveTraverse sealed private[sstream] trait SStreamConfigF[A]

private[sstream] object SStreamConfigF {

  final case class DefaultParams[K](tr: NJDateTimeRange, showDs: NJShowDataset)
      extends SStreamConfigF[K]

  final case class WithCheckpointReplace[K](value: String, cont: K) extends SStreamConfigF[K]
  final case class WithCheckpointAppend[K](value: String, cont: K) extends SStreamConfigF[K]

  final case class WithFailOnDataLoss[K](isFail: Boolean, cont: K) extends SStreamConfigF[K]
  final case class WithOutputMode[K](value: OutputMode, cont: K) extends SStreamConfigF[K]
  final case class WithTrigger[K](value: Trigger, cont: K) extends SStreamConfigF[K]

  final case class WithFormat[K](value: NJFileFormat, cont: K) extends SStreamConfigF[K]

  private val algebra: Algebra[SStreamConfigF, SStreamParams] =
    Algebra[SStreamConfigF, SStreamParams] {
      case DefaultParams(tr, sd)       => SStreamParams(tr, sd)
      case WithCheckpointReplace(v, c) => SStreamParams.checkpoint.set(NJCheckpoint(v))(c)
      case WithCheckpointAppend(v, c)  => SStreamParams.checkpoint.modify(_.append(v))(c)
      case WithFailOnDataLoss(v, c)    => SStreamParams.dataLoss.set(NJFailOnDataLoss(v))(c)
      case WithOutputMode(v, c)        => SStreamParams.outputMode.set(v)(c)
      case WithTrigger(v, c)           => SStreamParams.trigger.set(v)(c)
      case WithFormat(v, c)            => SStreamParams.fileFormat.set(v)(c)
    }

  def evalConfig(cfg: SStreamConfig): SStreamParams = scheme.cata(algebra).apply(cfg.value)

}

final private[sstream] case class SStreamConfig(value: Fix[SStreamConfigF]) extends AnyVal {
  import SStreamConfigF._

  def withCheckpointReplace(cp: String): SStreamConfig =
    SStreamConfig(Fix(WithCheckpointReplace(cp, value)))

  def withCheckpointAppend(cp: String): SStreamConfig =
    SStreamConfig(Fix(WithCheckpointAppend(cp, value)))

  def failOnDataLoss: SStreamConfig =
    SStreamConfig(Fix(WithFailOnDataLoss(isFail = true, value)))

  def ignoreDataLoss: SStreamConfig =
    SStreamConfig(Fix(WithFailOnDataLoss(isFail = false, value)))

  private def withOutputMode(f: OutputMode): SStreamConfig =
    SStreamConfig(Fix(WithOutputMode(f, value)))
  def withAppend: SStreamConfig   = withOutputMode(OutputMode.Append())
  def withComplete: SStreamConfig = withOutputMode(OutputMode.Complete())
  def withUpdate: SStreamConfig   = withOutputMode(OutputMode.Update())

  def withTrigger(trigger: Trigger): SStreamConfig =
    SStreamConfig(Fix(WithTrigger(trigger, value)))

  def withProcessingTimeTrigger(ms: Long): SStreamConfig =
    withTrigger(Trigger.ProcessingTime(ms, TimeUnit.MILLISECONDS))

  def withContinousTrigger(ms: Long): SStreamConfig =
    withTrigger(Trigger.Continuous(ms, TimeUnit.MILLISECONDS))

  def withJson: SStreamConfig    = SStreamConfig(Fix(WithFormat(NJFileFormat.Jackson, value)))
  def withParquet: SStreamConfig = SStreamConfig(Fix(WithFormat(NJFileFormat.Parquet, value)))
  def withAvro: SStreamConfig    = SStreamConfig(Fix(WithFormat(NJFileFormat.Avro, value)))

  def evalConfig: SStreamParams = SStreamConfigF.evalConfig(this)
}

private[spark] object SStreamConfig {

  def apply(tr: NJDateTimeRange, sd: NJShowDataset): SStreamConfig =
    SStreamConfig(Fix(SStreamConfigF.DefaultParams[Fix[SStreamConfigF]](tr, sd)))
}
