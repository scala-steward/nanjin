package com.github.chenharryhua.nanjin.sparkdb

import cats.effect.{Concurrent, ContextShift, Sync}
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.Read
import doobie.util.fragment.Fragment
import frameless.{TypedDataset, TypedEncoder}
import fs2.Stream
import org.apache.spark.sql.SparkSession

final case class TableDef[A](tableName: String)(
  implicit
  val typedEncoder: TypedEncoder[A],
  val doobieRead: Read[A]) {

  def in[F[_]: ContextShift: Concurrent](dbSettings: DatabaseSettings)(
    implicit sparkSession: SparkSession): TableDataset[F, A] =
    TableDataset[F, A](this, dbSettings, SparkTableParams.default)
}

final case class TableDataset[F[_]: ContextShift: Concurrent, A](
  tableDef: TableDef[A],
  dbSettings: DatabaseSettings,
  tableParams: SparkTableParams)(implicit sparkSession: SparkSession)
    extends TableParamModule[F, A] {
  import tableDef.{doobieRead, typedEncoder}

  // spark
  def datasetFromDB: TypedDataset[A] =
    TypedDataset.createUnsafe[A](
      spark.read
        .format("jdbc")
        .option("url", dbSettings.connStr.value)
        .option("driver", dbSettings.driver.value)
        .option("dbtable", tableDef.tableName)
        .load())

  def datasetFromDisk(path: String): TypedDataset[A] =
    TypedDataset.createUnsafe[A](
      spark.read
        .format(tableParams.format.value)
        .options(tableParams.format.defaultOptions ++ tableParams.sparkOptions)
        .load(path))

  def uploadToDB(data: TypedDataset[A]): F[Unit] =
    Sync[F].delay(
      data.write
        .mode(tableParams.saveMode)
        .format("jdbc")
        .option("url", dbSettings.connStr.value)
        .option("driver", dbSettings.driver.value)
        .option("dbtable", tableDef.tableName)
        .save())

  // doobie
  val source: Stream[F, A] = {
    for {
      xa <- dbSettings.transactorStream[F]
      dt: Stream[ConnectionIO, A] = (fr"select * from" ++ Fragment.const(tableDef.tableName))
        .query[A]
        .stream
      rst <- xa.transP.apply(dt)
    } yield rst
  }
}
