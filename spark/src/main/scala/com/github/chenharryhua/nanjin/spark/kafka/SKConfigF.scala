package com.github.chenharryhua.nanjin.spark.kafka

import java.time.{LocalDateTime, ZoneId}

import cats.Functor
import cats.data.Reader
import com.github.chenharryhua.nanjin.common.NJFileFormat
import com.github.chenharryhua.nanjin.datetime.NJDateTimeRange
import com.github.chenharryhua.nanjin.kafka.common.TopicName
import com.github.chenharryhua.nanjin.spark.{NJRepartition, NJShowDataset}
import higherkindness.droste.data.Fix
import higherkindness.droste.{scheme, Algebra}
import monocle.macros.Lenses
import org.apache.spark.sql.SaveMode
import org.apache.spark.streaming.kafka010.{LocationStrategies, LocationStrategy}

import scala.concurrent.duration._

@Lenses final private[spark] case class NJUploadRate(batchSize: Int, duration: FiniteDuration)

private[spark] object NJUploadRate {
  val default: NJUploadRate = NJUploadRate(batchSize = 1000, duration = 1.second)
}

final case class NJPathBuild(fileFormat: NJFileFormat, topicName: TopicName)

@Lenses final case class SKParams private (
  timeRange: NJDateTimeRange,
  uploadRate: NJUploadRate,
  pathBuilder: Reader[NJPathBuild, String],
  fileFormat: NJFileFormat,
  saveMode: SaveMode,
  locationStrategy: LocationStrategy,
  repartition: NJRepartition,
  showDs: NJShowDataset)

object SKParams {

  val default: SKParams =
    SKParams(
      timeRange        = NJDateTimeRange.infinite,
      uploadRate       = NJUploadRate.default,
      pathBuilder      = Reader(kpb => s"./data/spark/kafka/${kpb.topicName}/${kpb.fileFormat}/"),
      fileFormat       = NJFileFormat.Parquet,
      saveMode         = SaveMode.ErrorIfExists,
      locationStrategy = LocationStrategies.PreferConsistent,
      repartition      = NJRepartition(30),
      showDs           = NJShowDataset(20, isTruncate = false)
    )
}

sealed private[spark] trait SKConfigF[A]

private[spark] object SKConfigF {
  final case class DefaultParams[K]() extends SKConfigF[K]

  final case class WithBatchSize[K](value: Int, cont: K) extends SKConfigF[K]
  final case class WithDuration[K](value: FiniteDuration, cont: K) extends SKConfigF[K]

  final case class WithFileFormat[K](value: NJFileFormat, cont: K) extends SKConfigF[K]

  final case class WithStartTime[K](value: LocalDateTime, cont: K) extends SKConfigF[K]
  final case class WithEndTime[K](value: LocalDateTime, cont: K) extends SKConfigF[K]
  final case class WithZoneId[K](value: ZoneId, cont: K) extends SKConfigF[K]

  final case class WithTimeRange[K](value: NJDateTimeRange, cont: K) extends SKConfigF[K]

  final case class WithSaveMode[K](value: SaveMode, cont: K) extends SKConfigF[K]

  final case class WithLocationStrategy[K](value: LocationStrategy, cont: K) extends SKConfigF[K]

  final case class WithRepartition[K](value: NJRepartition, cont: K) extends SKConfigF[K]

  final case class WithShowRows[K](value: Int, cont: K) extends SKConfigF[K]
  final case class WithShowTruncate[K](value: Boolean, cont: K) extends SKConfigF[K]

  final case class WithPathBuilder[K](value: Reader[NJPathBuild, String], cont: K)
      extends SKConfigF[K]

  implicit val configParamFunctor: Functor[SKConfigF] =
    cats.derived.semi.functor[SKConfigF]

  final case class SKConfig(value: Fix[SKConfigF]) extends AnyVal {

    def withBatchSize(v: Int): SKConfig           = SKConfig(Fix(WithBatchSize(v, value)))
    def withDuration(v: FiniteDuration): SKConfig = SKConfig(Fix(WithDuration(v, value)))

    def withFileFormat(ff: NJFileFormat): SKConfig = SKConfig(Fix(WithFileFormat(ff, value)))

    def withStartTime(s: LocalDateTime): SKConfig    = SKConfig(Fix(WithStartTime(s, value)))
    def withEndTime(s: LocalDateTime): SKConfig      = SKConfig(Fix(WithEndTime(s, value)))
    def withZoneId(s: ZoneId): SKConfig              = SKConfig(Fix(WithZoneId(s, value)))
    def withTimeRange(tr: NJDateTimeRange): SKConfig = SKConfig(Fix(WithTimeRange(tr, value)))

    def withLocationStrategy(ls: LocationStrategy): SKConfig =
      SKConfig(Fix(WithLocationStrategy(ls, value)))

    def withRepartition(rp: Int): SKConfig =
      SKConfig(Fix(WithRepartition(NJRepartition(rp), value)))

    def withShowRows(num: Int): SKConfig = SKConfig(Fix(WithShowRows(num, value)))

    def withShowTruncate(truncate: Boolean): SKConfig =
      SKConfig(Fix(WithShowTruncate(truncate, value)))

    def withSaveMode(sm: SaveMode): SKConfig = SKConfig(Fix(WithSaveMode(sm, value)))

    def withPathBuilder(f: Reader[NJPathBuild, String]): SKConfig =
      SKConfig(Fix(WithPathBuilder(f, value)))
  }

  object SKConfig {
    val defaultParams: SKConfig = SKConfig(Fix(DefaultParams[Fix[SKConfigF]]()))
  }

  private val algebra: Algebra[SKConfigF, SKParams] = Algebra[SKConfigF, SKParams] {
    case DefaultParams()            => SKParams.default
    case WithBatchSize(v, c)        => SKParams.uploadRate.composeLens(NJUploadRate.batchSize).set(v)(c)
    case WithDuration(v, c)         => SKParams.uploadRate.composeLens(NJUploadRate.duration).set(v)(c)
    case WithFileFormat(v, c)       => SKParams.fileFormat.set(v)(c)
    case WithStartTime(v, c)        => SKParams.timeRange.modify(_.withStartTime(v))(c)
    case WithEndTime(v, c)          => SKParams.timeRange.modify(_.withEndTime(v))(c)
    case WithZoneId(v, c)           => SKParams.timeRange.modify(_.withZoneId(v))(c)
    case WithTimeRange(v, c)        => SKParams.timeRange.set(v)(c)
    case WithSaveMode(v, c)         => SKParams.saveMode.set(v)(c)
    case WithLocationStrategy(v, c) => SKParams.locationStrategy.set(v)(c)
    case WithRepartition(v, c)      => SKParams.repartition.set(v)(c)
    case WithShowRows(v, c)         => SKParams.showDs.composeLens(NJShowDataset.rowNum).set(v)(c)
    case WithShowTruncate(v, c)     => SKParams.showDs.composeLens(NJShowDataset.isTruncate).set(v)(c)
    case WithPathBuilder(v, c)      => SKParams.pathBuilder.set(v)(c)
  }

  def evalParams(params: SKConfig): SKParams = scheme.cata(algebra).apply(params.value)

}
