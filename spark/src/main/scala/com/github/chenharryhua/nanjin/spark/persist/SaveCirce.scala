package com.github.chenharryhua.nanjin.spark.persist

import cats.effect.{Blocker, ContextShift, Sync}
import com.github.chenharryhua.nanjin.devices.NJHadoop
import com.github.chenharryhua.nanjin.pipes.CirceSerialization
import com.github.chenharryhua.nanjin.spark.RddExt
import fs2.Pipe
import io.circe.{Json, Encoder => JsonEncoder}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag

final class SaveCirce[F[_], A](rdd: RDD[A], cfg: HoarderConfig, isKeepNull: Boolean) extends Serializable {
  val params: HoarderParams = cfg.evalConfig

  private def updateConfig(cfg: HoarderConfig): SaveCirce[F, A] =
    new SaveCirce[F, A](rdd, cfg, isKeepNull)

  def overwrite: SaveCirce[F, A]      = updateConfig(cfg.withOverwrite)
  def errorIfExists: SaveCirce[F, A]  = updateConfig(cfg.withError)
  def ignoreIfExists: SaveCirce[F, A] = updateConfig(cfg.withIgnore)

  def outPath(path: String): SaveCirce[F, A] = updateConfig(cfg.withOutPutPath(path))

  def keepNull: SaveCirce[F, A] = new SaveCirce[F, A](rdd, cfg, true)
  def dropNull: SaveCirce[F, A] = new SaveCirce[F, A](rdd, cfg, false)

  def file: SaveCirce[F, A]   = updateConfig(cfg.withSingleFile)
  def folder: SaveCirce[F, A] = updateConfig(cfg.withFolder)

  def gzip: SaveCirce[F, A] = updateConfig(cfg.withCompression(Compression.Gzip))

  def deflate(level: Int): SaveCirce[F, A] =
    updateConfig(cfg.withCompression(Compression.Deflate(level)))

  def run(blocker: Blocker)(implicit
    F: Sync[F],
    cs: ContextShift[F],
    jsonEncoder: JsonEncoder[A],
    tag: ClassTag[A]): F[Unit] = {

    val hadoopConfiguration = new Configuration(rdd.sparkContext.hadoopConfiguration)

    val sma: SaveModeAware[F] =
      new SaveModeAware[F](params.saveMode, params.outPath, hadoopConfiguration)

    val ccg: CompressionCodecGroup[F] = params.compression.ccg[F](hadoopConfiguration)

    params.folderOrFile match {
      case FolderOrFile.SingleFile =>
        val hadoop: NJHadoop[F]       = NJHadoop[F](hadoopConfiguration, blocker)
        val pipe: Pipe[F, A, Byte]    = new CirceSerialization[F, A].serialize(isKeepNull)
        val sink: Pipe[F, Byte, Unit] = hadoop.byteSink(params.outPath)
        sma.checkAndRun(blocker)(rdd.stream[F].through(pipe).through(ccg.compressionPipe).through(sink).compile.drain)

      case FolderOrFile.Folder =>
        def encode(a: A): Json =
          if (isKeepNull) jsonEncoder(a) else jsonEncoder(a).deepDropNullValues
        hadoopConfiguration.set(NJTextOutputFormat.suffix, params.format.suffix)
        rdd.sparkContext.hadoopConfiguration.addResource(hadoopConfiguration)
        sma.checkAndRun(blocker)(
          F.delay(
            rdd
              .map(x => (NullWritable.get(), new Text(encode(x).noSpaces)))
              .saveAsNewAPIHadoopFile[NJTextOutputFormat](params.outPath)))
    }
  }
}
