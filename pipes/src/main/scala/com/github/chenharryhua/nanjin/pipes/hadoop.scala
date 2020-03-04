package com.github.chenharryhua.nanjin.pipes

import java.io.OutputStream
import java.net.URI

import cats.effect.{Blocker, ContextShift, Resource, Sync}
import cats.implicits._
import com.sksamuel.avro4s.{
  AvroInputStream,
  AvroInputStreamBuilder,
  AvroOutputStream,
  AvroOutputStreamBuilder
}
import kantan.csv.{CsvConfiguration, CsvWriter, HeaderEncoder}
import org.apache.avro.Schema
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, FSDataOutputStream, FileSystem, Path}

object hadoop {

  def fileSystem[F[_]: Sync: ContextShift](
    pathStr: String,
    hadoopConfig: Configuration,
    blocker: Blocker): Resource[F, FileSystem] =
    Resource.fromAutoCloseable(blocker.delay(FileSystem.get(new URI(pathStr), hadoopConfig)))

  def outputPathResource[F[_]: Sync: ContextShift](
    pathStr: String,
    hadoopConfig: Configuration,
    blocker: Blocker): Resource[F, FSDataOutputStream] =
    for {
      fs <- fileSystem(pathStr, hadoopConfig, blocker)
      rs <- Resource.fromAutoCloseable(blocker.delay(fs.create(new Path(pathStr))))
    } yield rs

  def avroOutputResource[F[_]: Sync: ContextShift, A](
    pathStr: String,
    schema: Schema,
    hadoopConfig: Configuration,
    builder: AvroOutputStreamBuilder[A],
    blocker: Blocker): Resource[F, AvroOutputStream[A]] =
    for {
      os <- outputPathResource(pathStr, hadoopConfig, blocker)
      rs <- Resource.fromAutoCloseable(Sync[F].pure(builder.to(os).build(schema)))
    } yield rs

  def csvOutputResource[F[_]: Sync: ContextShift, A: HeaderEncoder](
    pathStr: String,
    hadoopConfig: Configuration,
    blocker: Blocker,
    csvConfig: CsvConfiguration): Resource[F, CsvWriter[A]] = {
    import kantan.csv.ops._
    for {
      os <- outputPathResource(pathStr, hadoopConfig, blocker).widen[OutputStream]
      rs <- Resource.fromAutoCloseable(Sync[F].pure(os.asCsvWriter[A](csvConfig)))
    } yield rs
  }

  def inputPathResource[F[_]: Sync: ContextShift](
    pathStr: String,
    hadoopConfig: Configuration,
    blocker: Blocker): Resource[F, FSDataInputStream] =
    for {
      fs <- fileSystem(pathStr, hadoopConfig, blocker)
      rs <- Resource.fromAutoCloseable(blocker.delay(fs.open(new Path(pathStr))))
    } yield rs

  def avroInputResource[F[_]: Sync: ContextShift, A](
    pathStr: String,
    schema: Schema,
    hadoopConfig: Configuration,
    builder: AvroInputStreamBuilder[A],
    blocker: Blocker): Resource[F, AvroInputStream[A]] =
    for {
      is <- inputPathResource(pathStr, hadoopConfig, blocker)
      rs <- Resource.fromAutoCloseable(Sync[F].pure(builder.from(is).build(schema)))
    } yield rs

  def delete[F[_]: Sync: ContextShift](
    pathStr: String,
    hadoopConfig: Configuration,
    blocker: Blocker): F[Boolean] =
    fileSystem(pathStr, hadoopConfig, blocker).use(fs =>
      blocker.delay(fs.delete(new Path(pathStr), true)))

}
