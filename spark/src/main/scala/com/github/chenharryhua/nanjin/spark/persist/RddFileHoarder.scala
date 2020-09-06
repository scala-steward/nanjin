package com.github.chenharryhua.nanjin.spark.persist

import cats.Show
import com.github.chenharryhua.nanjin.common.NJFileFormat._
import com.github.chenharryhua.nanjin.messages.kafka.codec.AvroCodec
import com.github.chenharryhua.nanjin.spark.AvroTypedEncoder
import frameless.TypedEncoder
import io.circe.{Encoder => JsonEncoder}
import kantan.csv.{CsvConfiguration, RowEncoder}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import scalapb.GeneratedMessage

import scala.reflect.ClassTag

final class RddFileHoarder[F[_], A: ClassTag](
  rdd: RDD[A],
  cfg: HoarderConfig = HoarderConfig.default)(implicit codec: AvroCodec[A], ss: SparkSession)
    extends Serializable {

  private def updateConfig(cfg: HoarderConfig): RddFileHoarder[F, A] =
    new RddFileHoarder[F, A](rdd, cfg)

  def overwrite: RddFileHoarder[F, A]      = updateConfig(cfg.withOverwrite)
  def errorIfExists: RddFileHoarder[F, A]  = updateConfig(cfg.withError)
  def ignoreIfExists: RddFileHoarder[F, A] = updateConfig(cfg.withIgnore)

  def repartition(num: Int): RddFileHoarder[F, A] =
    new RddFileHoarder[F, A](rdd.repartition(num), cfg)

// 1
  def jackson(outPath: String): SaveJackson[F, A] =
    new SaveJackson[F, A](rdd, cfg.withFormat(Jackson).withOutPutPath(outPath))

// 2
  def circe(outPath: String)(implicit ev: JsonEncoder[A]): SaveCirce[F, A] =
    new SaveCirce[F, A](rdd, cfg.withFormat(Circe).withOutPutPath(outPath))

// 3
  def text(outPath: String)(implicit ev: Show[A]): SaveText[F, A] =
    new SaveText[F, A](rdd, cfg.withFormat(Text).withOutPutPath(outPath))

// 4
  def csv(outPath: String)(implicit ev: RowEncoder[A], te: TypedEncoder[A]): SaveCsv[F, A] = {
    implicit val ate: AvroTypedEncoder[A] = AvroTypedEncoder[A](te, codec)
    new SaveCsv[F, A](rdd, CsvConfiguration.rfc, cfg.withFormat(Csv).withOutPutPath(outPath))
  }

  // 5
  def json(outPath: String)(implicit te: TypedEncoder[A]): SaveSparkJson[F, A] = {
    implicit val ate: AvroTypedEncoder[A] = AvroTypedEncoder[A](te, codec)
    new SaveSparkJson[F, A](rdd, cfg.withFormat(SparkJson).withOutPutPath(outPath))
  }

  // 11
  def parquet(outPath: String)(implicit te: TypedEncoder[A]): SaveParquet[F, A] = {
    implicit val ate: AvroTypedEncoder[A] = AvroTypedEncoder[A](te, codec)
    new SaveParquet[F, A](rdd, cfg.withFormat(Parquet).withOutPutPath(outPath))
  }

  // 12
  def avro(outPath: String): SaveAvro[F, A] =
    new SaveAvro[F, A](rdd, None, cfg.withFormat(Avro).withOutPutPath(outPath))

// 13
  def binAvro(outPath: String): SaveBinaryAvro[F, A] =
    new SaveBinaryAvro[F, A](rdd, cfg.withFormat(BinaryAvro).withOutPutPath(outPath))

// 14
  def objectFile(outPath: String): SaveObjectFile[F, A] =
    new SaveObjectFile[F, A](rdd, cfg.withFormat(JavaObject).withOutPutPath(outPath))

// 15
  def protobuf(outPath: String)(implicit ev: A <:< GeneratedMessage): SaveProtobuf[F, A] =
    new SaveProtobuf[F, A](rdd, cfg.withFormat(ProtoBuf).withOutPutPath(outPath))
}
