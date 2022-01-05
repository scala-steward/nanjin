package mtest.guard

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.chenharryhua.nanjin.guard.TaskGuard
import com.github.chenharryhua.nanjin.guard.event.MetricReport
import org.scalatest.Ignore
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.*

@Ignore
class PerformanceTest extends AnyFunSuite {
  val service =
    TaskGuard[IO]("performance").service("actions").updateConfig(_.withQueueCapacity(50).withMetricReport(3.seconds))
  val take   = 90.seconds
  val repeat = 1

  test("critical") {
    var i = 0
    val run = service.eventStream { ag =>
      ag.span("critical").critical.retry(IO(i += 1)).run.foreverM.timeout(take).attempt
    }.filter(_.isInstanceOf[MetricReport]).evalTap(IO.println).compile.drain
    run.replicateA(repeat).unsafeRunSync()
  }

  test("notice") {
    var i = 0
    val run = service.eventStream { ag =>
      ag.span("notice").notice.retry(IO(i += 1)).run.foreverM.timeout(take).attempt
    }.filter(_.isInstanceOf[MetricReport]).evalTap(IO.println).compile.drain
    run.replicateA(repeat).unsafeRunSync()
  }

  test("normal") {
    var i = 0
    val run = service.eventStream { ag =>
      ag.span("normal").normal.retry(IO(i += 1)).run.foreverM.timeout(take).attempt
    }.filter(_.isInstanceOf[MetricReport]).evalTap(IO.println).compile.drain
    run.replicateA(repeat).unsafeRunSync()
  }

  test("trivial") {
    var i = 0
    val run = service.eventStream { ag =>
      ag.span("trivial").trivial.retry(IO(i += 1)).run.foreverM.timeout(take).attempt
    }.filter(_.isInstanceOf[MetricReport]).evalTap(IO.println).compile.drain
    run.replicateA(repeat).unsafeRunSync()
  }

  test("critical - no timing") {
    var i = 0
    service.eventStream { ag =>
      ag.span("c").critical.updateConfig(_.withoutTiming).retry(IO(i += 1)).run.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(s"$i critical - no timing")
  }

  test("notice - no timing") {
    var i: Int = 0
    service.eventStream { ag =>
      ag.span("n").notice.updateConfig(_.withoutTiming).retry(IO(i += 1)).run.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(s"$i notice - no timing")
  }

  test("normal - no timing") {
    var i: Int = 0
    service.eventStream { ag =>
      ag.span("m").normal.updateConfig(_.withoutTiming).retry(IO(i += 1)).run.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(s"$i normal - no timing")
  }

  test("trivial - no timing") {
    var i = 0
    service.eventStream { ag =>
      ag.span("t").trivial.updateConfig(_.withoutTiming).retry(IO(i += 1)).run.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(s"$i trivial - no timing")
  }

}
