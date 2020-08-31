package mtest.spark.persist

import cats.effect.IO
import com.github.chenharryhua.nanjin.spark.persist.{loaders, savers}
import frameless.TypedDataset
import frameless.cats.implicits.framelessCatsSparkDelayForSync
import org.apache.spark.rdd.RDD
import org.scalatest.funsuite.AnyFunSuite

class AvroTest extends AnyFunSuite {
  import RoosterData._

  test("rdd read/write identity") {
    val path = "./data/test/spark/persist/avro/raw"
    delete(path)
    savers.raw.avro(rdd, path)
    val r: RDD[Rooster]          = loaders.rdd.avro[Rooster](path)
    val t: TypedDataset[Rooster] = loaders.tds.avro[Rooster](path)
    assert(expected == r.collect().toSet)
    assert(expected == t.collect[IO]().unsafeRunSync().toSet)
  }

  test("tds read/write identity") {
    val path = "./data/test/spark/persist/avro/spark"
    delete(path)
    savers.avro(rdd, path)
    val t: TypedDataset[Rooster] = loaders.tds.avro[Rooster](path)
    assert(expected == t.collect[IO]().unsafeRunSync().toSet)
  }
}
