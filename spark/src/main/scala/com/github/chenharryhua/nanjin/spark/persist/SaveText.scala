package com.github.chenharryhua.nanjin.spark.persist

import cats.Show
import cats.effect.Sync
import com.github.chenharryhua.nanjin.spark.RddExt
import org.apache.hadoop.conf.Configuration
import org.apache.spark.rdd.RDD

final class SaveText[F[_], A](rdd: RDD[A], cfg: HoarderConfig, suffix: String) extends Serializable {

  def withSuffix(suffix: String): SaveText[F, A] = new SaveText[F, A](rdd, cfg, suffix)

  def file: SaveSingleText[F, A]  = new SaveSingleText(rdd, cfg, suffix)
  def folder: SaveMultiText[F, A] = new SaveMultiText[F, A](rdd, cfg, suffix)

}

final class SaveSingleText[F[_], A](rdd: RDD[A], cfg: HoarderConfig, suffix: String) extends Serializable {
  val params: HoarderParams = cfg.evalConfig

  private def updateConfig(cfg: HoarderConfig): SaveSingleText[F, A] =
    new SaveSingleText[F, A](rdd, cfg, suffix)

  def overwrite: SaveSingleText[F, A]      = updateConfig(cfg.withOverwrite)
  def errorIfExists: SaveSingleText[F, A]  = updateConfig(cfg.withError)
  def ignoreIfExists: SaveSingleText[F, A] = updateConfig(cfg.withIgnore)

  def gzip: SaveSingleText[F, A]                = updateConfig(cfg.withCompression(Compression.Gzip))
  def deflate(level: Int): SaveSingleText[F, A] = updateConfig(cfg.withCompression(Compression.Deflate(level)))
  def uncompress: SaveSingleText[F, A]          = updateConfig(cfg.withCompression(Compression.Uncompressed))

  def run(implicit F: Sync[F], show: Show[A]): F[Unit] = {
    val hc: Configuration     = rdd.sparkContext.hadoopConfiguration
    val sma: SaveModeAware[F] = new SaveModeAware[F](params.saveMode, params.outPath, hc)
    sma.checkAndRun(
      rdd.stream[F].through(sinks.text(params.outPath, hc, params.compression.fs2Compression)).compile.drain)
  }
}

final class SaveMultiText[F[_], A](rdd: RDD[A], cfg: HoarderConfig, suffix: String) extends Serializable {
  val params: HoarderParams = cfg.evalConfig

  private def updateConfig(cfg: HoarderConfig): SaveMultiText[F, A] =
    new SaveMultiText[F, A](rdd, cfg, suffix)

  def append: SaveMultiText[F, A]         = updateConfig(cfg.withAppend)
  def overwrite: SaveMultiText[F, A]      = updateConfig(cfg.withOverwrite)
  def errorIfExists: SaveMultiText[F, A]  = updateConfig(cfg.withError)
  def ignoreIfExists: SaveMultiText[F, A] = updateConfig(cfg.withIgnore)

  def bzip2: SaveMultiText[F, A]               = updateConfig(cfg.withCompression(Compression.Bzip2))
  def gzip: SaveMultiText[F, A]                = updateConfig(cfg.withCompression(Compression.Gzip))
  def deflate(level: Int): SaveMultiText[F, A] = updateConfig(cfg.withCompression(Compression.Deflate(level)))
  def uncompress: SaveMultiText[F, A]          = updateConfig(cfg.withCompression(Compression.Uncompressed))

  def run(implicit F: Sync[F], show: Show[A]): F[Unit] =
    new SaveModeAware[F](params.saveMode, params.outPath, rdd.sparkContext.hadoopConfiguration)
      .checkAndRun(F.delay(saveRDD.text(rdd, params.outPath, params.compression, suffix)))
}
