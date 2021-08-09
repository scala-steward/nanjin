package mtest.guard

import better.files.*
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.codahale.metrics.jvm.ThreadStatesGaugeSet
import com.github.chenharryhua.nanjin.guard.*
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

class MetricsReporterTest extends AnyFunSuite {
  test("metrics reporter") {
    TaskGuard[IO]("task")
      .service("service")
      .addMetricReporter(NJConsoleReporter(2.second))
      .registerMetricSet(new ThreadStatesGaugeSet)
      .addMetricReporter(NJJmxReporter())
      .addMetricReporter(NJSlf4jReporter(3.second))
      .addMetricReporter(NJConsoleReporter(5.second).updateConfig(_.convertRatesTo(TimeUnit.HOURS)))
      .addMetricReporter(NJCsvReporter(File("./data/metrics").createDirectoryIfNotExists().path, 3.seconds))
      .eventStream(ag => ag.run(IO.println("running").delayBy(1.second)).foreverM)
      .interruptAfter(10.seconds)
      .compile
      .drain
      .unsafeRunSync()
  }
  test("no reporter") {
    TaskGuard[IO]("task")
      .service("service")
      .eventStream(ag => ag.run(IO.println("no reporter running").delayBy(1.second)).foreverM)
      .interruptAfter(3.seconds)
      .compile
      .drain
      .unsafeRunSync()
  }
}
