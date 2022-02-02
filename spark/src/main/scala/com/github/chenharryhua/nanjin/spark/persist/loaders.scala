package com.github.chenharryhua.nanjin.spark.persist

import akka.stream.IOResult
import akka.stream.scaladsl.Source
import cats.Eval
import cats.syntax.functor.*
import cats.effect.kernel.{Async, Sync}
import com.github.chenharryhua.nanjin.common.{ChunkSize, PathRoot, PathSegment}
import com.github.chenharryhua.nanjin.pipes.serde.{CirceSerde, JacksonSerde}
import com.github.chenharryhua.nanjin.spark.AvroTypedEncoder
import com.github.chenharryhua.nanjin.terminals.{AkkaHadoop, NJHadoop, NJParquet, NJPath}
import com.sksamuel.avro4s.{AvroInputStream, Decoder as AvroDecoder}
import fs2.Stream
import io.circe.Decoder as JsonDecoder
import io.circe.parser.decode
import kantan.csv.CsvConfiguration
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericRecord}
import org.apache.avro.io.DecoderFactory
import org.apache.avro.mapred.AvroKey
import org.apache.avro.mapreduce.{AvroJob, AvroKeyInputFormat}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.mapreduce.Job
import org.apache.parquet.avro.AvroParquetReader
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Dataset, SparkSession}
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import squants.information.Information

import java.io.DataInputStream
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.Try

object loaders {

  def avro[A](path: NJPath, ate: AvroTypedEncoder[A], ss: SparkSession): Dataset[A] =
    ate.normalizeDF(ss.read.format("avro").load(path.pathStr))

  def parquet[A](path: NJPath, ate: AvroTypedEncoder[A], ss: SparkSession): Dataset[A] =
    ate.normalizeDF(ss.read.parquet(path.pathStr))

  def csv[A](path: NJPath, ate: AvroTypedEncoder[A], csvConfiguration: CsvConfiguration, ss: SparkSession): Dataset[A] =
    ate.normalizeDF(
      ss.read
        .schema(ate.sparkSchema)
        .option("sep", csvConfiguration.cellSeparator.toString)
        .option("header", csvConfiguration.hasHeader)
        .option("quote", csvConfiguration.quote.toString)
        .option("charset", "UTF8")
        .csv(path.pathStr))

  def csv[A](path: NJPath, ate: AvroTypedEncoder[A], ss: SparkSession): Dataset[A] =
    csv[A](path, ate, CsvConfiguration.rfc, ss)

  def json[A](path: NJPath, ate: AvroTypedEncoder[A], ss: SparkSession): Dataset[A] =
    ate.normalizeDF(ss.read.schema(ate.sparkSchema).json(path.pathStr))

  def objectFile[A](path: NJPath, ate: AvroTypedEncoder[A], ss: SparkSession): Dataset[A] =
    ate.normalize(rdd.objectFile[A](path, ss)(ate.classTag), ss)

  def circe[A](path: NJPath, ate: AvroTypedEncoder[A], ss: SparkSession)(implicit dec: JsonDecoder[A]): Dataset[A] =
    ate.normalize(rdd.circe[A](path, ss)(ate.classTag, dec), ss)

  def jackson[A](path: NJPath, ate: AvroTypedEncoder[A], ss: SparkSession): Dataset[A] =
    ate.normalize(rdd.jackson[A](path, ate.avroCodec.avroDecoder, ss)(ate.classTag), ss)

  def binAvro[A](path: NJPath, ate: AvroTypedEncoder[A], ss: SparkSession): Dataset[A] =
    ate.normalize(rdd.binAvro[A](path, ate.avroCodec.avroDecoder, ss)(ate.classTag), ss)

  object rdd {

    def objectFile[A: ClassTag](path: NJPath, ss: SparkSession): RDD[A] =
      ss.sparkContext.objectFile[A](path.pathStr)

    def circe[A: ClassTag: JsonDecoder](path: NJPath, ss: SparkSession): RDD[A] =
      ss.sparkContext
        .textFile(path.pathStr)
        .map(decode[A](_) match {
          case Left(ex) => throw ex
          case Right(r) => r
        })

    def protobuf[A <: GeneratedMessage: ClassTag](path: NJPath, ss: SparkSession)(implicit
      decoder: GeneratedMessageCompanion[A]): RDD[A] =
      ss.sparkContext
        .binaryFiles(path.pathStr)
        .mapPartitions(_.flatMap { case (_, pds) =>
          val dis: DataInputStream = pds.open()
          val itor: Iterator[A]    = decoder.streamFromDelimitedInput(dis).iterator
          new Iterator[A] {
            override def hasNext: Boolean =
              if (itor.hasNext) true else { Try(dis.close()); false }

            override def next(): A = itor.next()
          }
        })

    def avro[A: ClassTag](path: NJPath, decoder: AvroDecoder[A], ss: SparkSession): RDD[A] = {
      val job = Job.getInstance(ss.sparkContext.hadoopConfiguration)
      AvroJob.setDataModelClass(job, classOf[GenericData])
      AvroJob.setInputKeySchema(job, decoder.schema)
      ss.sparkContext.hadoopConfiguration.addResource(job.getConfiguration)

      ss.sparkContext
        .newAPIHadoopFile(
          path.pathStr,
          classOf[AvroKeyInputFormat[GenericRecord]],
          classOf[AvroKey[GenericRecord]],
          classOf[NullWritable])
        .map { case (gr, _) => decoder.decode(gr.datum()) }
    }

    def binAvro[A: ClassTag](path: NJPath, decoder: AvroDecoder[A], ss: SparkSession): RDD[A] =
      ss.sparkContext
        .binaryFiles(path.pathStr)
        .mapPartitions(_.flatMap { case (_, pds) => // resource leak ???
          val dis: DataInputStream = pds.open()
          val itor: Iterator[A] =
            AvroInputStream.binary[A](decoder).from(dis).build(decoder.schema).iterator
          new Iterator[A] {
            override def hasNext: Boolean =
              if (itor.hasNext) true else { Try(dis.close()); false }

            override def next(): A = itor.next()
          }
        })

    def jackson[A: ClassTag](path: NJPath, decoder: AvroDecoder[A], ss: SparkSession): RDD[A] = {
      val schema: Schema = decoder.schema
      ss.sparkContext.textFile(path.pathStr).mapPartitions { strs =>
        val datumReader: GenericDatumReader[GenericRecord] = new GenericDatumReader[GenericRecord](schema)
        strs.map { str =>
          val jsonDecoder = DecoderFactory.get().jsonDecoder(schema, str)
          decoder.decode(datumReader.read(null, jsonDecoder))
        }
      }
    }
  }

  object stream {

    def jackson[F[_]: Async, A](path: NJPath, decoder: AvroDecoder[A], cfg: Configuration): Stream[F, A] = {
      val hdp: NJHadoop[F] = NJHadoop[F](cfg)
      val fsa: F[Stream[F, A]] = hdp
        .locatedFileStatus(path)
        .map(_.filter(_.isFile).foldLeft(Stream.empty.covaryAll[F, A]) { case (ss, lfs) =>
          ss ++ hdp
            .byteSource(NJPath(lfs.getPath))
            .through(JacksonSerde.deserPipe(decoder.schema))
            .map(decoder.decode)
            .handleErrorWith(_ => Stream.empty)
        })
      Stream.force(fsa)
    }

    def avro[F[_]: Sync, A](
      path: NJPath,
      decoder: AvroDecoder[A],
      cfg: Configuration,
      chunkSize: ChunkSize): Stream[F, A] = {
      val hdp: NJHadoop[F] = NJHadoop[F](cfg)
      val fsa: F[Stream[F, A]] = hdp
        .locatedFileStatus(path)
        .map(_.filter(_.isFile).foldLeft(Stream.empty.covaryAll[F, A]) { case (ss, lfs) =>
          ss ++ hdp
            .avroSource(NJPath(lfs.getPath), decoder.schema, chunkSize)
            .map(decoder.decode)
            .handleErrorWith(_ => Stream.empty)
        })
      Stream.force(fsa)
    }

    def circe[F[_]: Sync, A: JsonDecoder](path: NJPath, cfg: Configuration): Stream[F, A] = {
      val hdp: NJHadoop[F] = NJHadoop[F](cfg)
      val fsa: F[Stream[F, A]] = hdp
        .locatedFileStatus(path)
        .map(_.filter(_.isFile).foldLeft(Stream.empty.covaryAll[F, A]) { case (ss, lfs) =>
          ss ++ hdp
            .byteSource(NJPath(lfs.getPath))
            .through(CirceSerde.deserPipe[F, A])
            .handleErrorWith(_ => Stream.empty)
        })
      Stream.force(fsa)
    }
  }

  object source {
    def avro[A](path: NJPath, decoder: AvroDecoder[A], cfg: Configuration): Source[A, Future[IOResult]] =
      AkkaHadoop(cfg).avroSource(path, decoder.schema).map(decoder.decode)

    def parquet[A](
      builder: Eval[AvroParquetReader.Builder[GenericRecord]],
      decoder: AvroDecoder[A]): Source[A, Future[IOResult]] =
      NJParquet.akkaSource(builder).map(decoder.decode)

  }
}
