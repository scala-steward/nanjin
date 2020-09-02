package mtest.spark.persist

import cats.effect.IO
import com.github.chenharryhua.nanjin.spark.persist.{loaders, RddFileHoader}
import frameless.TypedDataset
import frameless.cats.implicits.framelessCatsSparkDelayForSync
import org.apache.spark.rdd.RDD
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite

@DoNotDiscover
class ParquetTest extends AnyFunSuite {
  import RoosterData._
  val saver = new RddFileHoader[IO, Rooster](rdd)
  test("datetime rdd read/write identity") {
    val path = "./data/test/spark/persist/parquet/rooster/raw"
    delete(path)
    saver.parquet(path).raw.run(blocker).unsafeRunSync()
    val r: RDD[Rooster] = loaders.raw.parquet[Rooster](path)
    assert(expected == r.collect().toSet)
  }

  test("datetime tds read/write identity") {
    val path = "./data/test/spark/persist/parquet/rooster/spark"
    delete(path)
    saver.parquet(path).spark.run(blocker).unsafeRunSync()
    val t: TypedDataset[Rooster] = loaders.parquet[Rooster](path)
    assert(expected == t.collect[IO]().unsafeRunSync().toSet)
  }

  test("byte-array rdd read/write identity") {
    import BeeData._
    import cats.implicits._
    val path = "./data/test/spark/persist/parquet/bee/raw"
    delete(path)
    val saver = new RddFileHoader[IO, Bee](rdd)
    saver.parquet(path).raw.run(blocker).unsafeRunSync()
    val t = loaders.raw.parquet[Bee](path)
    assert(bees.sortBy(_.b).zip(t.collect().toList.sortBy(_.b)).forall { case (a, b) => a.eqv(b) })
  }

  test("byte-array spark read/write identity") {
    import BeeData._
    import cats.implicits._
    val path = "./data/test/spark/persist/parquet/bee/spark"
    delete(path)
    val saver = new RddFileHoader[IO, Bee](rdd)
    saver.parquet(path).spark.run(blocker).unsafeRunSync()
    val t = loaders.parquet[Bee](path).collect[IO].unsafeRunSync().toList
    assert(bees.sortBy(_.b).zip(t.sortBy(_.b)).forall { case (a, b) => a.eqv(b) })
  }

  test("collection raw read/write identity") {
    import AntData._
    val path = "./data/test/spark/persist/parquet/ant/raw"
    delete(path)
    val saver = new RddFileHoader[IO, Ant](rdd)
    saver.parquet(path).raw.run(blocker).unsafeRunSync()
    val t = loaders.raw.parquet[Ant](path)
    assert(ants.toSet == t.collect().toSet)
  }

  test("collection spark read/write identity") {
    import AntData._
    val path = "./data/test/spark/persist/avro/ant/spark"
    delete(path)
    val saver = new RddFileHoader[IO, Ant](rdd)
    saver.parquet(path).spark.run(blocker).unsafeRunSync()
    val t = loaders.parquet[Ant](path)
    assert(ants.toSet == t.collect[IO]().unsafeRunSync().toSet)
  }
}
