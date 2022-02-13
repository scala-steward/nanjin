package com.github.chenharryhua.nanjin.terminals

import cats.effect.kernel.Sync
import kantan.csv.CsvConfiguration
import org.apache.avro.Schema
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.*
import org.apache.parquet.hadoop.util.HiddenFileFilter

import scala.collection.mutable.ListBuffer

object NJHadoop {

  def apply[F[_]: Sync](config: Configuration): NJHadoop[F] = new NJHadoop[F](config)
}

final class NJHadoop[F[_]] private (config: Configuration)(implicit F: Sync[F]) {

  // disk operations

  def delete(path: NJPath): F[Boolean] = F.blocking {
    val fs = path.hadoopPath.getFileSystem(config)
    fs.delete(path.hadoopPath, true)
  }

  def isExist(path: NJPath): F[Boolean] = F.blocking {
    val fs = path.hadoopPath.getFileSystem(config)
    fs.exists(path.hadoopPath)
  }

  def locatedFileStatus(path: NJPath): F[List[LocatedFileStatus]] = F.blocking {
    val fs = path.hadoopPath.getFileSystem(config)
    val ri = fs.listFiles(path.hadoopPath, true)
    val lb = ListBuffer.empty[LocatedFileStatus]
    while (ri.hasNext) lb.addOne(ri.next())
    lb.toList
  }

  // folders which contain data files
  def dataFolders(path: NJPath): F[List[NJPath]] = F.blocking {
    val fs = path.hadoopPath.getFileSystem(config)
    val ri = fs.listFiles(path.hadoopPath, true)
    val lb = collection.mutable.Set.empty[Path]
    while (ri.hasNext) lb.addOne(ri.next().getPath.getParent)
    lb.toList.map(NJPath(_)).sortBy(_.toString)
  }

  def filesIn[A: Ordering](path: NJPath, sorting: FileStatus => A): F[List[NJPath]] = F.blocking {
    val fs: FileSystem   = path.hadoopPath.getFileSystem(config)
    val stat: FileStatus = fs.getFileStatus(path.hadoopPath)
    if (stat.isFile)
      List(NJPath(stat.getPath))
    else
      fs.listStatus(path.hadoopPath, HiddenFileFilter.INSTANCE)
        .filter(_.isFile)
        .sortBy(sorting)
        .map(s => NJPath(s.getPath))
        .toList
  }
  def filesByTime(path: NJPath): F[List[NJPath]] = filesIn(path, _.getModificationTime)
  def filesByName(path: NJPath): F[List[NJPath]] = filesIn(path, _.getPath.getName)

  // sources and sinks
  def bytes: NJBytes[F]                     = NJBytes[F](config)
  def avro(schema: Schema): NJAvro[F]       = NJAvro[F](schema, config)
  def parquet(schema: Schema): NJParquet[F] = NJParquet[F](schema, config)
  def csv(cfg: CsvConfiguration): NJCsv[F]  = NJCsv[F](cfg, config)
}
