package com.github.chenharryhua.nanjin.spark.database

import com.github.chenharryhua.nanjin.spark.persist.{DatasetAvroFileHoarder, HoarderConfig}
import com.github.chenharryhua.nanjin.terminals.NJPath
import com.zaxxer.hikari.HikariConfig
import frameless.TypedDataset
import org.apache.spark.sql.Dataset

final class TableDS[F[_], A] private[database] (
  val dataset: Dataset[A],
  tableDef: TableDef[A],
  hikariConfig: HikariConfig,
  cfg: STConfig)
    extends Serializable {

  val params: STParams = cfg.evalConfig

  def transform(f: Dataset[A] => Dataset[A]): TableDS[F, A] =
    new TableDS[F, A](f(dataset), tableDef, hikariConfig, cfg)

  def normalize: TableDS[F, A] = transform(tableDef.ate.normalize(_))

  def repartition(num: Int): TableDS[F, A] = transform(_.repartition(num))

  def map[B](f: A => B)(tdb: TableDef[B]): TableDS[F, B] =
    new TableDS[F, B](dataset.map(f)(tdb.ate.sparkEncoder), tdb, hikariConfig, cfg).normalize

  def flatMap[B](f: A => IterableOnce[B])(tdb: TableDef[B]): TableDS[F, B] =
    new TableDS[F, B](dataset.flatMap(f)(tdb.ate.sparkEncoder), tdb, hikariConfig, cfg).normalize

  def typedDataset: TypedDataset[A] = TypedDataset.create(dataset)(tableDef.ate.typedEncoder)

  def upload: DbUploader[F, A] =
    new DbUploader[F, A](dataset, hikariConfig, cfg)

  def save(path: NJPath): DatasetAvroFileHoarder[F, A] =
    new DatasetAvroFileHoarder[F, A](dataset, tableDef.ate.avroCodec.avroEncoder, HoarderConfig(path))

}
