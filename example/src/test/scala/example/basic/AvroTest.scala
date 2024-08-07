package example.basic

import cats.effect.IO
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.common.chrono.zones.{beijingTime, sydneyTime}
import com.github.chenharryhua.nanjin.guard.service.Agent
import com.github.chenharryhua.nanjin.terminals.{AvroFile, NJCompression, NJPath}
import eu.timepit.refined.auto.*
import example.hadoop

class AvroTest(agent: Agent[IO], base: NJPath) extends WriteRead(agent) {
  private val root = base / "avro"

  private val files: List[AvroFile] = List(
    AvroFile(NJCompression.Uncompressed),
    AvroFile(NJCompression.Xz(2)),
    AvroFile(NJCompression.Deflate(2)),
    AvroFile(NJCompression.Bzip2),
    AvroFile(NJCompression.Snappy)
  )

  private val avro = hadoop.avro(schema)

  private def writeSingle(file: AvroFile): IO[NJPath] = {
    val path = root / "single" / file.fileName
    val sink = avro.withCompression(file.compression).sink(path)
    write(path.uri.getPath).use { meter =>
      data.evalTap(_ => meter.update(1)).map(encoder.to).chunks.through(sink).compile.drain.as(path)
    }
  }

  private def writeRotate(file: AvroFile): IO[NJPath] = {
    val path = root / "rotate" / file.fileName
    val sink = avro.withCompression(file.compression).sink(policy, sydneyTime)(t => path / file.fileName(t))
    write(path.uri.getPath).use { meter =>
      data.evalTap(_ => meter.update(1)).map(encoder.to).chunks.through(sink).compile.drain.as(path)
    }
  }

  private def writeSingleSpark(file: AvroFile): IO[NJPath] = {
    val path = root / "spark" / "single" / file.fileName
    val sink = avro.withCompression(file.compression).sink(path)
    write(path.uri.getPath).use { meter =>
      table
        .stream[IO](1000)
        .evalTap(_ => meter.update(1))
        .map(encoder.to)
        .chunks
        .through(sink)
        .compile
        .drain
        .as(path)
    }
  }

  private def writeMultiSpark(file: AvroFile): IO[NJPath] = {
    val path = root / "spark" / "multi" / file.fileName
    write(path.uri.getPath).use(_ =>
      table.output.avro(path).withCompression(file.compression).run[IO].as(path))
  }

  private def writeRotateSpark(file: AvroFile): IO[NJPath] = {
    val path = root / "spark" / "rotate" / file.fileName
    val sink = avro.withCompression(file.compression).sink(policy, beijingTime)(t => path / file.fileName(t))
    write(path.uri.getPath).use { meter =>
      table
        .stream[IO](1000)
        .evalTap(_ => meter.update(1))
        .map(encoder.to)
        .chunks
        .through(sink)
        .compile
        .drain
        .as(path)
    }
  }

  private def sparkRead(path: NJPath): IO[Long] =
    read(path.uri.getPath).use(_ => loader.avro(path).count[IO])

  private def folderRead(path: NJPath): IO[Long] =
    read(path.uri.getPath).use { meter =>
      hadoop
        .filesIn(path)
        .flatMap(
          _.traverse(avro.source(_, 1000).map(decoder.from).evalTap(_ => meter.update(1)).compile.fold(0L) {
            case (s, _) => s + 1
          }))
        .map(_.sum)
    }

  private def singleRead(path: NJPath): IO[Long] =
    read(path.uri.getPath).use { meter =>
      avro.source(path, 1000).map(decoder.from).evalTap(_ => meter.update(1)).compile.fold(0L) {
        case (s, _) =>
          s + 1
      }
    }

  val single: IO[List[Long]] =
    files.parTraverse(writeSingle).flatMap(ps => ps.parTraverse(singleRead) >> ps.traverse(sparkRead))

  val rotate: IO[List[Long]] =
    files.parTraverse(writeRotate).flatMap(ps => ps.parTraverse(folderRead) >> ps.traverse(sparkRead))

  val sparkSingle: IO[List[Long]] =
    files.parTraverse(writeSingleSpark).flatMap(ps => ps.parTraverse(singleRead) >> ps.traverse(sparkRead))

  val sparkRotate: IO[List[Long]] =
    files.parTraverse(writeRotateSpark).flatMap(ps => ps.parTraverse(folderRead) >> ps.traverse(sparkRead))

  val sparkMulti: IO[List[Long]] =
    files.traverse(writeMultiSpark).flatMap(ps => ps.parTraverse(folderRead) >> ps.traverse(sparkRead))

}
