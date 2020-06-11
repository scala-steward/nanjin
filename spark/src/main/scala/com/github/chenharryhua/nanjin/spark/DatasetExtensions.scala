package com.github.chenharryhua.nanjin.spark

import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.Eq
import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import frameless.cats.implicits._
import frameless.{TypedDataset, TypedEncoder}
import fs2.interop.reactivestreams._
import fs2.{Pipe, Stream}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.reflect.ClassTag

private[spark] trait DatasetExtensions {

  implicit class RddExt[A](private val rdd: RDD[A]) {

    def stream[F[_]: Sync]: Stream[F, A] =
      Stream.fromIterator(rdd.toLocalIterator.flatMap(Option(_)))

    def source[F[_]: ConcurrentEffect]: Source[A, NotUsed] =
      Source.fromPublisher[A](stream[F].toUnicastPublisher())

    def typedDataset(implicit ev: TypedEncoder[A], ss: SparkSession): TypedDataset[A] =
      TypedDataset.create(rdd)

    def partitionOutput[F[_]: ConcurrentEffect, K: Eq: ClassTag: Ordering](bucketing: A => K)(
      out: K => Pipe[F, A, Unit]): F[Unit] = {
      val persisted: RDD[A] = rdd.persist()
      persisted
        .groupBy(bucketing)
        .aggregate(Set.empty[K])(
          { case (s, (k, _)) => s + k },
          (s1, s2) => s1 ++ s2
        )
        .toList
        .sorted
        .map(k => persisted.filter(a => k === bucketing(a)).stream[F].through(out(k)))
        .reduce(_ ++ _)
        .compile
        .drain >> ConcurrentEffect[F].delay(persisted.unpersist())
    }
  }

  implicit class TypedDatasetExt[A](private val tds: TypedDataset[A]) {

    def stream[F[_]: Sync]: Stream[F, A] = tds.dataset.rdd.stream[F]

    def source[F[_]: ConcurrentEffect]: Source[A, NotUsed] =
      Source.fromPublisher[A](stream[F].toUnicastPublisher())

    def validRecords: TypedDataset[A] = {
      import tds.encoder
      tds.deserialized.flatMap(Option(_))
    }

    def invalidRecords: TypedDataset[A] =
      tds.except(validRecords)
  }

  implicit class DataframeExt(private val df: DataFrame) {

    def genCaseClass: String = NJDataTypeF.genCaseClass(df.schema)

  }

  implicit class SparkSessionExt(private val ss: SparkSession) {

    def parquet[A: TypedEncoder](pathStr: String): TypedDataset[A] =
      TypedDataset.createUnsafe[A](ss.read.parquet(pathStr))

    def avro[A: TypedEncoder](pathStr: String): TypedDataset[A] =
      TypedDataset.createUnsafe(ss.read.format("avro").load(pathStr))

    def text(path: String): TypedDataset[String] =
      TypedDataset.create(ss.read.textFile(path))
  }
}
