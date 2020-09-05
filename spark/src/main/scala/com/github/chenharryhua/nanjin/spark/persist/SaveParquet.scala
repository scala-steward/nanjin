package com.github.chenharryhua.nanjin.spark.persist

import cats.{Eq, Parallel}
import cats.effect.{Blocker, Concurrent, ContextShift}
import com.github.chenharryhua.nanjin.common.NJFileFormat
import com.github.chenharryhua.nanjin.messages.kafka.codec.AvroCodec
import com.github.chenharryhua.nanjin.spark.{fileSink, utils, RddExt}
import com.sksamuel.avro4s.{Encoder => AvroEncoder}
import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.mapreduce.Job
import org.apache.parquet.avro.{AvroParquetOutputFormat, GenericDataSupplier}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SaveMode, SparkSession}

import scala.reflect.ClassTag

final class SaveParquet[F[_], A: ClassTag](rdd: RDD[A], cfg: HoarderConfig)(implicit
  codec: AvroCodec[A],
  ss: SparkSession)
    extends Serializable {
  val params: HoarderParams = cfg.evalConfig

  private def updateConfig(cfg: HoarderConfig): SaveParquet[F, A] =
    new SaveParquet[F, A](rdd, cfg)

  def spark: SaveParquet[F, A] = updateConfig(cfg.withSpark)
  def raw: SaveParquet[F, A]   = updateConfig(cfg.withRaw)

  def file: SaveParquet[F, A]   = updateConfig(cfg.withSingleFile)
  def folder: SaveParquet[F, A] = updateConfig(cfg.withFolder)

  def run(blocker: Blocker)(implicit F: Concurrent[F], cs: ContextShift[F]): F[Unit] = {
    implicit val encoder: AvroEncoder[A] = codec.avroEncoder
    val sma: SaveModeAware[F]            = new SaveModeAware[F](params.saveMode, params.outPath, ss)

    (params.singleOrMulti, params.sparkOrRaw) match {
      case (FolderOrFile.SingleFile, _) =>
        sma.checkAndRun(blocker)(
          rdd.stream[F].through(fileSink[F](blocker).parquet(params.outPath)).compile.drain)
      case (FolderOrFile.Folder, SparkOrRaw.Spark) =>
        sma.checkAndRun(blocker)(
          F.delay(
            utils
              .normalizedDF(rdd, codec.avroEncoder)
              .write
              .mode(SaveMode.Overwrite)
              .parquet(params.outPath)))
      case (FolderOrFile.Folder, SparkOrRaw.Raw) =>
        val sparkjob = F.delay {
          val job = Job.getInstance(ss.sparkContext.hadoopConfiguration)
          AvroParquetOutputFormat.setAvroDataSupplier(job, classOf[GenericDataSupplier])
          AvroParquetOutputFormat.setSchema(job, codec.schema)
          ss.sparkContext.hadoopConfiguration.addResource(job.getConfiguration)
          rdd // null as java Void
            .map(a => (null, codec.avroEncoder.encode(a).asInstanceOf[GenericRecord]))
            .saveAsNewAPIHadoopFile(
              params.outPath,
              classOf[Void],
              classOf[GenericRecord],
              classOf[AvroParquetOutputFormat[GenericRecord]])
        }
        sma.checkAndRun(blocker)(sparkjob)
    }
  }
}

final class PartitionParquet[F[_], A: ClassTag, K: ClassTag: Eq](
  rdd: RDD[A],
  cfg: HoarderConfig,
  bucketing: A => Option[K],
  pathBuilder: (NJFileFormat, K) => String)(implicit codec: AvroCodec[A], ss: SparkSession)
    extends AbstractPartition[F, A, K] {

  val params: HoarderParams = cfg.evalConfig

  def run(
    blocker: Blocker)(implicit F: Concurrent[F], CS: ContextShift[F], P: Parallel[F]): F[Unit] =
    savePartition(
      blocker,
      rdd,
      params.parallelism,
      params.format,
      bucketing,
      pathBuilder,
      (r, p) => new SaveParquet[F, A](r, cfg.withOutPutPath(p)).run(blocker))
}
