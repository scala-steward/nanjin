package com.github.chenharryhua.nanjin.spark.database

import com.github.chenharryhua.nanjin.common.NJFileFormat
import com.github.chenharryhua.nanjin.database.{DatabaseName, DatabaseSettings, TableName}
import com.github.chenharryhua.nanjin.messages.kafka.codec.NJAvroCodec
import com.github.chenharryhua.nanjin.spark.saver.RddFileLoader
import com.sksamuel.avro4s.{Decoder => AvroDecoder, Encoder => AvroEncoder}
import frameless.{TypedDataset, TypedEncoder}
import io.circe.{Decoder => JsonDecoder}
import kantan.csv.CsvConfiguration
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

final case class TableDef[A] private (
  tableName: TableName,
  typedEncoder: TypedEncoder[A],
  avroEncoder: AvroEncoder[A],
  avroDecoder: AvroDecoder[A]) {

  def in[F[_]](dbSettings: DatabaseSettings)(implicit
    sparkSession: SparkSession): SparkTable[F, A] =
    new SparkTable[F, A](this, dbSettings, STConfig(dbSettings.database, tableName), sparkSession)
}

object TableDef {

  def apply[A: AvroEncoder: AvroDecoder: TypedEncoder](tableName: TableName): TableDef[A] =
    new TableDef[A](tableName, TypedEncoder[A], AvroEncoder[A], AvroDecoder[A])

  def apply[A](tableName: TableName, schema: NJAvroCodec[A])(implicit
    typedEncoder: TypedEncoder[A]) =
    new TableDef[A](tableName, typedEncoder, schema.avroEncoder, schema.avroDecoder)
}

final class SparkTable[F[_], A](
  tableDef: TableDef[A],
  dbSettings: DatabaseSettings,
  cfg: STConfig,
  ss: SparkSession)
    extends Serializable {
  implicit val sparkSession: SparkSession    = ss
  implicit val avroDecoder: AvroDecoder[A]   = tableDef.avroDecoder
  implicit val avroEncoder: AvroEncoder[A]   = tableDef.avroEncoder
  implicit val typedEncoder: TypedEncoder[A] = tableDef.typedEncoder
  import tableDef.typedEncoder.classTag

  val params: STParams = cfg.evalConfig

  val tableName: TableName = tableDef.tableName

  def withQuery(query: String): SparkTable[F, A] =
    new SparkTable[F, A](tableDef, dbSettings, cfg.withQuery(query), ss)

  def withPathBuilder(f: (DatabaseName, TableName, NJFileFormat) => String): SparkTable[F, A] =
    new SparkTable[F, A](tableDef, dbSettings, cfg.withPathBuilder(f), ss)

  def fromDB: TableDataset[F, A] =
    new TableDataset[F, A](
      sd.unloadDS(dbSettings.connStr, dbSettings.driver, tableDef.tableName, params.query).dataset,
      dbSettings,
      cfg)

  def tableDataset(rdd: RDD[A]): TableDataset[F, A] =
    new TableDataset[F, A](TypedDataset.create(rdd).dataset, dbSettings, cfg)

  object load extends Serializable {
    private val loader: RddFileLoader = new RddFileLoader(sparkSession)

    def avro(pathStr: String): TableDataset[F, A] = tableDataset(loader.avro[A](pathStr))
    def avro: TableDataset[F, A]                  = avro(params.outPath(NJFileFormat.Avro))

    def binAvro(pathStr: String): TableDataset[F, A] = tableDataset(loader.binAvro[A](pathStr))
    def binAvro: TableDataset[F, A]                  = binAvro(params.outPath(NJFileFormat.BinaryAvro))

    def parquet(pathStr: String): TableDataset[F, A] = tableDataset(loader.parquet[A](pathStr))
    def parquet: TableDataset[F, A]                  = parquet(params.outPath(NJFileFormat.Parquet))

    def jackson(pathStr: String): TableDataset[F, A] = tableDataset(loader.jackson[A](pathStr))
    def jackson: TableDataset[F, A]                  = jackson(params.outPath(NJFileFormat.Jackson))

    def circe(pathStr: String)(implicit ev: JsonDecoder[A]): TableDataset[F, A] =
      tableDataset(loader.circe[A](pathStr))

    def circe(implicit ev: JsonDecoder[A]): TableDataset[F, A] =
      circe(params.outPath(NJFileFormat.Circe))

    def csv(pathStr: String, csvConfig: CsvConfiguration): TableDataset[F, A] =
      tableDataset(loader.csv(pathStr, csvConfig))

    def csv(pathStr: String): TableDataset[F, A] =
      csv(pathStr, CsvConfiguration.rfc)

    def csv: TableDataset[F, A] =
      csv(params.outPath(NJFileFormat.Csv))

    def text(pathStr: String)(f: String => A): TableDataset[F, A] =
      tableDataset(loader.text(pathStr).map(f))
  }
}
