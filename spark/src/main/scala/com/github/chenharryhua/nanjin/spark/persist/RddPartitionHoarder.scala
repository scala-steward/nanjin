package com.github.chenharryhua.nanjin.spark.persist

import cats.Eq
import cats.implicits._
import com.github.chenharryhua.nanjin.messages.kafka.codec.NJAvroCodec
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import io.circe.{Encoder => JsonEncoder}
import kantan.csv.{CsvConfiguration, RowEncoder}

import scala.reflect.ClassTag
import cats.Show
import com.github.chenharryhua.nanjin.common.NJFileFormat
import com.github.chenharryhua.nanjin.common.NJFileFormat.{
  Avro,
  BinaryAvro,
  Circe,
  Csv,
  Jackson,
  Parquet,
  SparkJson,
  Text
}

class RddPartitionHoarder[F[_], A: ClassTag, K: Eq: ClassTag](
  rdd: RDD[A],
  bucketing: A => Option[K],
  pathBuilder: (NJFileFormat, K) => String,
  cfg: HoarderConfig = HoarderConfig.default)(implicit codec: NJAvroCodec[A], ss: SparkSession)
    extends Serializable {

  private def updateConfig(cfg: HoarderConfig): RddPartitionHoarder[F, A, K] =
    new RddPartitionHoarder[F, A, K](rdd, bucketing, pathBuilder, cfg)

  def errorIfExists: RddPartitionHoarder[F, A, K]  = updateConfig(cfg.withError)
  def overwrite: RddPartitionHoarder[F, A, K]      = updateConfig(cfg.withOverwrite)
  def ignoreIfExists: RddPartitionHoarder[F, A, K] = updateConfig(cfg.withIgnore)

  def file: RddPartitionHoarder[F, A, K]   = updateConfig(cfg.withSingleFile)
  def folder: RddPartitionHoarder[F, A, K] = updateConfig(cfg.withFolder)

  def spark: RddPartitionHoarder[F, A, K] = updateConfig(cfg.withSpark)
  def raw: RddPartitionHoarder[F, A, K]   = updateConfig(cfg.withRaw)

  def parallel(num: Long): RddPartitionHoarder[F, A, K] =
    updateConfig(cfg.withParallel(num))

  def reBucket[K1: ClassTag: Eq](
    bucketing: A => Option[K1],
    pathBuilder: (NJFileFormat, K1) => String): RddPartitionHoarder[F, A, K1] =
    new RddPartitionHoarder[F, A, K1](rdd, bucketing, pathBuilder, cfg)

  def rePath(pathBuilder: (NJFileFormat, K) => String): RddPartitionHoarder[F, A, K] =
    new RddPartitionHoarder[F, A, K](rdd, bucketing, pathBuilder, cfg)

  def avro: PartitionAvro[F, A, K] =
    new PartitionAvro[F, A, K](rdd, cfg.withFormat(Avro), bucketing, pathBuilder)

  def parquet: PartitionParquet[F, A, K] =
    new PartitionParquet[F, A, K](rdd, cfg.withFormat(Parquet), bucketing, pathBuilder)

  def binAvro: PartitionBinaryAvro[F, A, K] =
    new PartitionBinaryAvro[F, A, K](rdd, cfg.withFormat(BinaryAvro), bucketing, pathBuilder)

  def jackson: PartitionJackson[F, A, K] =
    new PartitionJackson[F, A, K](rdd, cfg.withFormat(Jackson), bucketing, pathBuilder)

  def json: PartitionSparkJson[F, A, K] =
    new PartitionSparkJson[F, A, K](rdd, cfg.withFormat(SparkJson), bucketing, pathBuilder)

  def text(implicit ev: Show[A]): PartitionText[F, A, K] =
    new PartitionText[F, A, K](rdd, cfg.withFormat(Text), bucketing, pathBuilder)

  def circe(implicit ev: JsonEncoder[A]): PartitionCirce[F, A, K] =
    new PartitionCirce[F, A, K](rdd, cfg.withFormat(Circe), bucketing, pathBuilder)

  def csv(implicit ev: RowEncoder[A]): PartitionCsv[F, A, K] =
    new PartitionCsv[F, A, K](
      rdd,
      CsvConfiguration.rfc,
      cfg.withFormat(Csv),
      bucketing,
      pathBuilder)
}
