package com.github.chenharryhua.nanjin

import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.kernel.Sync
import com.github.chenharryhua.nanjin.common.ChunkSize
import com.github.chenharryhua.nanjin.kafka.{KafkaContext, KafkaTopic}
import com.github.chenharryhua.nanjin.spark.database.{DbUploader, SparkDBTable}
import com.github.chenharryhua.nanjin.spark.kafka.SparKafkaTopic
import com.github.chenharryhua.nanjin.spark.persist.*
import com.github.chenharryhua.nanjin.terminals.{NJHadoop, NJPath}
import com.sksamuel.avro4s.Encoder as AvroEncoder
import com.zaxxer.hikari.HikariConfig
import fs2.Stream
import org.apache.avro.Schema
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}

import scala.reflect.ClassTag

package object spark {

  object injection extends InjectionInstances

  private[spark] val SparkDatetimeConversionConstant: Int = 1000

  implicit final class RddExt[A](rdd: RDD[A]) extends Serializable {

    def dismissNulls(implicit ev: ClassTag[A]): RDD[A] = rdd.flatMap(Option(_))
    def numOfNulls(implicit ev: ClassTag[A]): Long     = rdd.subtract(dismissNulls).count()

    def stream[F[_]: Sync](chunkSize: ChunkSize): Stream[F, A] =
      Stream.fromBlockingIterator(rdd.toLocalIterator, chunkSize.value)

    def source: Source[A, NotUsed] = Source.fromIterator(() => rdd.toLocalIterator)

    def dbUpload[F[_]: Sync](db: SparkDBTable[F, A]): DbUploader[F, A] =
      db.tableset(rdd).upload

    def save[F[_]](path: NJPath): RddFileHoarder[F, A] = new RddFileHoarder[F, A](rdd, HoarderConfig(path))

    def save[F[_]](path: NJPath, encoder: AvroEncoder[A]): RddAvroFileHoarder[F, A] =
      new RddAvroFileHoarder[F, A](rdd, encoder, HoarderConfig(path))

  }

  implicit final class DatasetExt[A](ds: Dataset[A]) extends Serializable {

    def stream[F[_]: Sync](chunkSize: ChunkSize): Stream[F, A] = ds.rdd.stream[F](chunkSize)
    def source: Source[A, NotUsed]                             = ds.rdd.source

    def dismissNulls: Dataset[A] = ds.flatMap(Option(_))(ds.encoder)
    def numOfNulls: Long         = ds.except(dismissNulls).count()

    def dbUpload[F[_]: Sync](db: SparkDBTable[F, A]): DbUploader[F, A] = db.tableset(ds).upload

    def save[F[_]](path: NJPath): DatasetFileHoarder[F, A] = new DatasetFileHoarder[F, A](ds, HoarderConfig(path))

    def save[F[_]](path: NJPath, encoder: AvroEncoder[A]): DatasetAvroFileHoarder[F, A] =
      new DatasetAvroFileHoarder[F, A](ds, encoder, HoarderConfig(path))
  }

  implicit final class DataframeExt(df: DataFrame) extends Serializable {

    def genCaseClass: String  = NJDataType(df.schema).toCaseClass
    def genSchema: Schema     = NJDataType(df.schema).toSchema
    def genDataType: DataType = NJDataType(df.schema).toSpark

  }

  implicit final class SparkSessionExt(ss: SparkSession) extends Serializable {

    def alongWith[F[_]](hikariConfig: HikariConfig): SparkDBContext[F] =
      new SparkDBContext[F](ss, hikariConfig)

    def alongWith[F[_]](ctx: KafkaContext[F]): SparKafkaContext[F] =
      new SparKafkaContext[F](ss, ctx)

    def topic[F[_], K, V](topic: KafkaTopic[F, K, V]): SparKafkaTopic[F, K, V] =
      new SparKafkaContext[F](ss, topic.context).topic(topic)

    def hadoop[F[_]: Sync]: NJHadoop[F] = NJHadoop[F](ss.sparkContext.hadoopConfiguration)

  }
}
