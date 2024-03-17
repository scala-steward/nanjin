package com.github.chenharryhua.nanjin.spark.persist

import cats.effect.kernel.Sync
import com.github.chenharryhua.nanjin.terminals.AvroCompression
import com.sksamuel.avro4s.Encoder as AvroEncoder
import org.apache.spark.rdd.RDD

final class SaveAvro[F[_], A](frdd: F[RDD[A]], encoder: AvroEncoder[A], cfg: HoarderConfig)
    extends Serializable with BuildRunnable[F] {

  private def updateConfig(cfg: HoarderConfig): SaveAvro[F, A] =
    new SaveAvro[F, A](frdd, encoder, cfg)

  val params: HoarderParams = cfg.evalConfig

  def append: SaveAvro[F, A]         = updateConfig(cfg.appendMode)
  def overwrite: SaveAvro[F, A]      = updateConfig(cfg.overwriteMode)
  def errorIfExists: SaveAvro[F, A]  = updateConfig(cfg.errorMode)
  def ignoreIfExists: SaveAvro[F, A] = updateConfig(cfg.ignoreMode)

  def withCompression(ac: AvroCompression): SaveAvro[F, A] = updateConfig(cfg.outputCompression(ac))
  def withCompression(f: AvroCompression.type => AvroCompression): SaveAvro[F, A] =
    withCompression(f(AvroCompression))

  def run(implicit F: Sync[F]): F[Unit] =
    F.flatMap(frdd) { rdd =>
      new SaveModeAware[F](params.saveMode, params.outPath, rdd.sparkContext.hadoopConfiguration)
        .checkAndRun(F.interruptible(saveRDD.avro(rdd, params.outPath, encoder, params.compression)))
    }
}
