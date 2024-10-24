package mtest.terminals
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.toTraverseOps
import com.github.chenharryhua.nanjin.common.chrono.Policy
import com.github.chenharryhua.nanjin.terminals.NJCompression.*
import com.github.chenharryhua.nanjin.terminals.{CsvHeaderOf, KantanFile, NJFileKind, NJHadoop}
import eu.timepit.refined.auto.*
import fs2.Stream
import io.circe.jawn
import io.circe.syntax.EncoderOps
import io.lemonlabs.uri.Url
import io.lemonlabs.uri.typesafe.dsl.*
import kantan.csv.{CsvConfiguration, RowDecoder, RowEncoder}
import mtest.terminals.HadoopTestData.hdp
import mtest.terminals.TestData.*
import org.apache.hadoop.conf.Configuration
import org.scalatest.Assertion
import org.scalatest.funsuite.AnyFunSuite

import java.time.ZoneId
import scala.concurrent.duration.DurationInt

class NJKantanTest extends AnyFunSuite {
  val tigerEncoder: RowEncoder[Tiger]          = shapeless.cachedImplicit
  val tigerDecoder: RowDecoder[Tiger]          = shapeless.cachedImplicit
  implicit val tigerHeader: CsvHeaderOf[Tiger] = shapeless.cachedImplicit

  def fs2(path: Url, file: KantanFile, csvConfiguration: CsvConfiguration, data: Set[Tiger]): Assertion = {
    val tgt = path / file.fileName
    hdp.delete(tgt).unsafeRunSync()
    val ts     = Stream.emits(data.toList).covary[IO].map(tigerEncoder.encode).chunks
    val sink   = hdp.kantan(csvConfiguration).sink(tgt)
    val src    = hdp.kantan(csvConfiguration).source(tgt, 100).map(tigerDecoder.decode).rethrow
    val action = ts.through(sink).compile.drain >> src.compile.toList
    assert(action.unsafeRunSync().toSet == data)
    val fileName = (file: NJFileKind).asJson.noSpaces
    assert(jawn.decode[NJFileKind](fileName).toOption.get == file)
    val size = ts.through(sink).fold(0)(_ + _).compile.lastOrError.unsafeRunSync()
    assert(size == data.size)
  }

  val fs2Root: Url = Url.parse("./data/test/terminals/csv/tiger")

  test("uncompressed - with-header") {
    val cfg = CsvConfiguration.rfc.withHeader(tigerHeader.modify(_ + "_tiger"))
    fs2(fs2Root / "header", KantanFile(_.Uncompressed), cfg, tigerSet)
  }

  test("uncompressed - with-implicit-header") {
    val cfg = CsvConfiguration.rfc.withHeader
    fs2(fs2Root / "header-implicit", KantanFile(_.Uncompressed), cfg, tigerSet)
  }

  test("uncompressed - without-header") {
    val cfg = CsvConfiguration.rfc.withHeader(false)
    fs2(fs2Root / "no-header", KantanFile(_.Uncompressed), cfg, tigerSet)
  }

  test("gzip") {
    val cfg = CsvConfiguration.rfc
    fs2(fs2Root, KantanFile(_.Gzip), cfg, tigerSet)
  }
  test("snappy") {
    val cfg = CsvConfiguration.rfc
    fs2(fs2Root, KantanFile(_.Snappy), cfg, tigerSet)
  }
  test("bzip2") {
    val cfg = CsvConfiguration.rfc.withCellSeparator('?')
    fs2(fs2Root, KantanFile(_.Bzip2), cfg, tigerSet)
  }
  test("lz4") {
    val cfg = CsvConfiguration.rfc.withQuotePolicy(CsvConfiguration.QuotePolicy.WhenNeeded)
    fs2(fs2Root, KantanFile(_.Lz4), cfg, tigerSet)
  }

  test("deflate") {
    val cfg = CsvConfiguration.rfc.withQuote('*')
    fs2(fs2Root, KantanFile(_.Deflate(6)), cfg, tigerSet)
  }

  test("ftp") {
    val path = Url.parse("ftp://localhost/data/tiger.csv")
    val conf = new Configuration()
    conf.set("fs.ftp.host", "localhost")
    conf.set("fs.ftp.user.localhost", "chenh")
    conf.set("fs.ftp.password.localhost", "test")
    conf.set("fs.ftp.data.connection.mode", "PASSIVE_LOCAL_DATA_CONNECTION_MODE")
    conf.set("fs.ftp.impl", "org.apache.hadoop.fs.ftp.FTPFileSystem")
    val conn = NJHadoop[IO](conf).kantan(CsvConfiguration.rfc)
    Stream
      .emits(tigerSet.toList)
      .covary[IO]
      .map(tigerEncoder.encode)
      .chunks
      .through(conn.sink(path))
      .compile
      .drain
      .unsafeRunSync()
  }

  test("laziness") {
    hdp.kantan(CsvConfiguration.rfc).source("./does/not/exist", 100)
    hdp.kantan(CsvConfiguration.rfc).sink("./does/not/exist")
  }

  val policy: Policy = Policy.fixedDelay(1.second)
  test("rotation - with-header") {
    val csv  = hdp.kantan(_.withHeader(CsvHeaderOf[Tiger].header))
    val path = fs2Root / "rotation" / "header" / "data"
    val file = KantanFile(Uncompressed)
    hdp.delete(path).unsafeRunSync()
    herd
      .map(tigerEncoder.encode)
      .chunks
      .through(csv.sink(policy, ZoneId.systemDefault())(t => path / file.fileName(t)))
      .compile
      .drain
      .unsafeRunSync()

    val size =
      hdp
        .filesIn(path)
        .flatMap(_.traverse(csv.source(_, 1000).map(tigerDecoder.decode).rethrow.compile.toList.map(_.size)))
        .map(_.sum)
        .unsafeRunSync()
    assert(size == herd_number)
  }

  test("rotation - empty(with header)") {
    val csv  = hdp.kantan(_.withHeader(CsvHeaderOf[Tiger].header))
    val path = fs2Root / "rotation" / "header" / "empty"
    hdp.delete(path).unsafeRunSync()
    val fk = KantanFile(Uncompressed)
    (Stream.sleep[IO](10.hours) >>
      Stream.empty.covaryAll[IO, Seq[String]]).chunks
      .through(csv.sink(Policy.fixedDelay(1.second).limited(3), ZoneId.systemDefault())(t =>
        path / fk.fileName(t)))
      .compile
      .drain
      .unsafeRunSync()
    import better.files.*
    hdp.filesIn(path).unsafeRunSync().foreach(np => assert(File(np.toJavaURI).lines.size == 1))
  }

  test("rotation - no header") {
    val csv    = hdp.kantan(CsvConfiguration.rfc)
    val path   = fs2Root / "rotation" / "no-header" / "data"
    val number = 10000L
    val file   = KantanFile(Uncompressed)
    hdp.delete(path).unsafeRunSync()
    herd
      .map(tigerEncoder.encode)
      .chunks
      .through(csv.sink(policy, ZoneId.systemDefault())(t => path / file.fileName(t)).andThen(_.drain))
      .map(tigerDecoder.decode)
      .rethrow
      .compile
      .drain
      .unsafeRunSync()
    val size =
      hdp
        .filesIn(path)
        .flatMap(_.traverse(csv.source(_, 1000).map(tigerDecoder.decode).rethrow.compile.toList.map(_.size)))
        .map(_.sum)
        .unsafeRunSync()
    assert(size == number)
  }

  test("rotation - empty(no header)") {
    val csv  = hdp.kantan(CsvConfiguration.rfc)
    val path = fs2Root / "rotation" / "no-header" / "empty"
    hdp.delete(path).unsafeRunSync()
    val fk = KantanFile(Uncompressed)
    (Stream.sleep[IO](10.hours) >>
      Stream.empty.covaryAll[IO, Seq[String]]).chunks
      .through(csv.sink(Policy.fixedDelay(1.second).limited(3), ZoneId.systemDefault())(t =>
        path / fk.fileName(t)))
      .compile
      .drain
      .unsafeRunSync()
    import better.files.*
    hdp.filesIn(path).unsafeRunSync().foreach(np => assert(File(np.toJavaURI).lines.isEmpty))
  }
}
