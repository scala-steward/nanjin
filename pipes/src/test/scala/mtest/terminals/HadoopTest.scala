package mtest.terminals

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.pipes.serde.BinaryAvroSerde
import com.github.chenharryhua.nanjin.terminals.{NJHadoop, NJPath}
import eu.timepit.refined.auto.*
import fs2.Stream
import org.apache.avro.Schema
import org.apache.avro.file.CodecFactory
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.compress.DeflateCodec
import org.scalatest.funsuite.AnyFunSuite
import squants.information.InformationConversions.*

import scala.util.Random
object HadoopTestData {

  val pandaSchema: Schema = (new Schema.Parser).parse("""
                                                        |{
                                                        |  "type": "record",
                                                        |  "name": "Panda",
                                                        |  "namespace": "mtest.HadoopTestData",
                                                        |  "fields": [
                                                        |    {
                                                        |      "name": "name",
                                                        |      "type": "string"
                                                        |    },
                                                        |    {
                                                        |      "name": "age",
                                                        |      "type": "int"
                                                        |    },
                                                        |    {
                                                        |      "name": "id",
                                                        |      "type": "int"
                                                        |    }
                                                        |  ]
                                                        |}
                                                        |""".stripMargin)

  val youngPanda = new GenericData.Record(pandaSchema)
  youngPanda.put("name", "zhouzhou")
  youngPanda.put("age", 8)
  youngPanda.put("id", Random.nextInt())

  val prettyPanda = new GenericData.Record(pandaSchema)
  prettyPanda.put("name", "fanfan")
  prettyPanda.put("age", 8)
  prettyPanda.put("id", Random.nextInt())

  val pandas: List[GenericRecord] = List(youngPanda, prettyPanda)

  val cfg               = new Configuration()
  val hdp: NJHadoop[IO] = NJHadoop[IO](cfg)

}

class HadoopTest extends AnyFunSuite {
  import HadoopTestData.*

  test("hadoop text write/read identity") {
    val pathStr = NJPath("./data/test/devices/") / "greeting.txt"
    hdp.delete(pathStr).unsafeRunSync()
    val testString = s"hello hadoop ${Random.nextInt()}"
    val ts: Stream[IO, Byte] =
      Stream(testString).through(fs2.text.utf8.encode)

    val action =
      ts.through(hdp.bytes.sink(pathStr)).compile.drain >>
        hdp.bytes.source(pathStr).through(fs2.text.utf8.decode).compile.toList
    assert(action.unsafeRunSync().mkString == testString)
  }

  test("snappy avro write/read") {
    val pathStr = NJPath("./data/test/devices/panda.snappy.avro")
    hdp.delete(pathStr).unsafeRunSync()
    val ts = Stream.emits(pandas).covary[IO]
    val action = hdp.delete(pathStr) >>
      ts.through(hdp.avro(pandaSchema).withCodecFactory(CodecFactory.snappyCodec).sink(pathStr)).compile.drain >>
      hdp.avro(pandaSchema).source(pathStr).compile.toList
    assert(action.unsafeRunSync() == pandas)
  }

  test("deflate(6) avro write/read") {
    val pathStr = NJPath("./data/test/devices/panda.deflate.avro")
    hdp.delete(pathStr).unsafeRunSync()
    val ts = Stream.emits(pandas).covary[IO]
    val action =
      ts.through(hdp.avro(pandaSchema).withCodecFactory(CodecFactory.deflateCodec(6)).sink(pathStr)).compile.drain >>
        hdp.avro(pandaSchema).source(pathStr).compile.toList
    assert(action.unsafeRunSync() == pandas)
  }

  test("uncompressed avro write/read") {
    val pathStr = NJPath("./data/test/devices/panda.uncompressed.avro")
    hdp.delete(pathStr).unsafeRunSync()
    val ts = Stream.emits(pandas).covary[IO]
    val action =
      ts.through(hdp.avro(pandaSchema).sink(pathStr)).compile.drain >>
        hdp.avro(pandaSchema).source(pathStr).compile.toList
    assert(action.unsafeRunSync() == pandas)
  }

  test("deflate binary avro write/read") {
    val pathStr = NJPath("./data/test/devices/panda.binary.avro.deflate")
    hdp.delete(pathStr).unsafeRunSync()
    val ts    = Stream.emits(pandas).covary[IO]
    val bytes = hdp.bytes.withCompressionCodec(new DeflateCodec())
    val action =
      ts.through(BinaryAvroSerde.serPipe(pandaSchema)).through(bytes.sink(pathStr)).compile.drain >>
        bytes.source(pathStr).through(BinaryAvroSerde.deserPipe(pandaSchema)).compile.toList
    assert(action.unsafeRunSync() == pandas)
  }

  test("extension voilation") {
    val pathStr = NJPath("./data/test/devices/panda.binary.avro.deflate2")
    hdp.delete(pathStr).unsafeRunSync()
    val ts     = Stream.emits(pandas).covary[IO]
    val bytes  = hdp.bytes.withCompressionCodec(new DeflateCodec())
    val action = ts.through(BinaryAvroSerde.serPipe(pandaSchema)).through(bytes.sink(pathStr)).compile.drain
    assertThrows[Exception](action.unsafeRunSync())
  }

  test("uncompressed binary avro write/read") {
    val pathStr = NJPath("./data/test/devices/panda.uncompressed.binary.avro")
    hdp.delete(pathStr).unsafeRunSync()
    val ts    = Stream.emits(pandas).covary[IO]
    val bytes = hdp.bytes
    val action =
      ts.through(BinaryAvroSerde.serPipe(pandaSchema)).through(bytes.sink(pathStr)).compile.drain >>
        bytes.source(pathStr).through(BinaryAvroSerde.deserPipe(pandaSchema)).compile.toList
    assert(action.unsafeRunSync() == pandas)
  }

  test("uncompressed avro write/read akka") {
    val pathStr = NJPath("./data/test/devices/akka/panda.uncompressed.avro")
    hdp.delete(pathStr).unsafeRunSync()
    val ts                         = Source(pandas)
    val avro                       = hdp.avro(pandaSchema).withCodecFactory(CodecFactory.nullCodec())
    val sink                       = avro.akka.sink(pathStr)
    val src                        = avro.akka.source(pathStr)
    implicit val mat: Materializer = Materializer(akkaSystem)
    val action =
      IO.fromFuture(IO(ts.runWith(sink))) >>
        IO.fromFuture(IO(src.runFold(List.empty[GenericRecord])(_.appended(_))))

    assert(action.unsafeRunSync() == pandas)
  }

  test("dataFolders") {
    val pathStr = NJPath("./data/test/devices")
    val folders = hdp.dataFolders(pathStr).unsafeRunSync()
    assert(folders.headOption.exists(_.pathStr.contains("devices")))
  }

  test("hadoop input files") {
    val path = NJPath("data/test/devices")
    hdp.filesByName(path).flatMap(_.traverse(x => IO.println(x.toString))).unsafeRunSync()
  }
}
