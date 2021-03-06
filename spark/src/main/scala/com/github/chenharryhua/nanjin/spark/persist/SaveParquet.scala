package com.github.chenharryhua.nanjin.spark.persist

import cats.effect.Sync
import com.github.chenharryhua.nanjin.spark.RddExt
import com.sksamuel.avro4s.Encoder as AvroEncoder
import fs2.Stream
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.spark.sql.Dataset

final class SaveParquet[F[_], A](ds: Dataset[A], encoder: AvroEncoder[A], cfg: HoarderConfig) extends Serializable {
  def file: SaveSingleParquet[F, A]  = new SaveSingleParquet[F, A](ds, encoder, cfg)
  def folder: SaveMultiParquet[F, A] = new SaveMultiParquet[F, A](ds, encoder, cfg)
}

final class SaveSingleParquet[F[_], A](ds: Dataset[A], encoder: AvroEncoder[A], cfg: HoarderConfig)
    extends Serializable {

  val params: HoarderParams = cfg.evalConfig

  private def updateConfig(cfg: HoarderConfig): SaveSingleParquet[F, A] =
    new SaveSingleParquet[F, A](ds, encoder, cfg)

  def overwrite: SaveSingleParquet[F, A]      = updateConfig(cfg.overwrite_mode)
  def errorIfExists: SaveSingleParquet[F, A]  = updateConfig(cfg.error_mode)
  def ignoreIfExists: SaveSingleParquet[F, A] = updateConfig(cfg.ignore_mode)

//  def brotli: SaveSingleParquet[F, A]     = updateConfig(cfg.withCompression(Compression.Brotli))
//  def lzo: SaveSingleParquet[F, A]        = updateConfig(cfg.withCompression(Compression.Lzo))
//  def lz4: SaveSingleParquet[F, A]        = updateConfig(cfg.withCompression(Compression.Lz4))
  def snappy: SaveSingleParquet[F, A]     = updateConfig(cfg.output_compression(Compression.Snappy))
  def gzip: SaveSingleParquet[F, A]       = updateConfig(cfg.output_compression(Compression.Gzip))
  def uncompress: SaveSingleParquet[F, A] = updateConfig(cfg.output_compression(Compression.Uncompressed))

  def stream(implicit F: Sync[F]): Stream[F, Unit] = {
    val hc: Configuration         = ds.sparkSession.sparkContext.hadoopConfiguration
    val sma: SaveModeAware[F]     = new SaveModeAware[F](params.saveMode, params.outPath, hc)
    val ccn: CompressionCodecName = params.compression.parquet
    sma.checkAndRun(ds.rdd.stream[F].through(sinks.parquet(params.outPath, hc, encoder, ccn)))
  }
}

final class SaveMultiParquet[F[_], A](ds: Dataset[A], encoder: AvroEncoder[A], cfg: HoarderConfig)
    extends Serializable {

  val params: HoarderParams = cfg.evalConfig

  private def updateConfig(cfg: HoarderConfig): SaveMultiParquet[F, A] =
    new SaveMultiParquet[F, A](ds, encoder, cfg)

  def append: SaveMultiParquet[F, A]         = updateConfig(cfg.append_mode)
  def overwrite: SaveMultiParquet[F, A]      = updateConfig(cfg.overwrite_mode)
  def errorIfExists: SaveMultiParquet[F, A]  = updateConfig(cfg.error_mode)
  def ignoreIfExists: SaveMultiParquet[F, A] = updateConfig(cfg.ignore_mode)

  def snappy: SaveMultiParquet[F, A]     = updateConfig(cfg.output_compression(Compression.Snappy))
  def gzip: SaveMultiParquet[F, A]       = updateConfig(cfg.output_compression(Compression.Gzip))
  def uncompress: SaveMultiParquet[F, A] = updateConfig(cfg.output_compression(Compression.Uncompressed))

  def run(implicit F: Sync[F]): F[Unit] =
    new SaveModeAware[F](params.saveMode, params.outPath, ds.sparkSession.sparkContext.hadoopConfiguration)
      .checkAndRun(F.delay {
        ds.write.option("compression", params.compression.name).mode(params.saveMode).parquet(params.outPath)
      })
}
