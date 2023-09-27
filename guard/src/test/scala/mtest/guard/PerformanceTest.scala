package mtest.guard

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.chenharryhua.nanjin.common.chrono.policies
import com.github.chenharryhua.nanjin.guard.TaskGuard
import com.github.chenharryhua.nanjin.guard.service.ServiceGuard
import io.circe.syntax.EncoderOps
import org.scalatest.Ignore
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.*

// sbt "guard/testOnly mtest.guard.PerformanceTest"

/** last time: (run more than once, pick up the best)
  *
  * trace:1143K/s
  *
  * silent.time.count: 847K/s
  *
  * aware.time.count: 353K/s
  *
  * aware.time.count.notes: 406K/s
  *
  * notice.time.count: 310K/s
  *
  * notice.time.count.notes: 291K/s
  *
  * silent.time: 777K/s
  *
  * aware.time: 347K/s
  *
  * aware.time.notes: 393K/s
  *
  * notice.time: 289K/s
  *
  * notice.time.notes: 301K/s
  *
  * silent.count: 1061K/s
  *
  * aware.count: 503K/s
  *
  * aware.count.notes: 465K/s
  *
  * notice.count: 349K/s
  *
  * notice.count.notes: 339K/s
  *
  * silent: 1122K/s
  *
  * aware: 206K/s
  *
  * aware.notes: 466K/s
  *
  * notice: 349K/s
  *
  * notice.notes: 344K/s
  */

@Ignore
class PerformanceTest extends AnyFunSuite {
  val service: ServiceGuard[IO] =
    TaskGuard[IO]("performance").service("actions").withMetricReport(policies.crontab(cron_1second))
  val take: FiniteDuration = 15.seconds

  def speed(i: Int): String = f"${i / (take.toSeconds * 1000)}%4dK/s"

  ignore("alert") {
    print("alert:")
    var i = 0
    service.eventStream { ag =>
      val ts = ag.alert("alert").info("alert").map(_ => i += 1)
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))
  }

  test("silent.time.count") {
    print("silent.time.count: ")
    var i = 0
    service.eventStream { ag =>
      val ts = ag.action("t", _.silent.withTiming.withCounting).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))
  }

  test("aware.time.count") {
    print("aware.time.count: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag.action("t", _.aware.withCounting.withTiming).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))
  }

  test("aware.time.count.notes") {
    print("aware.time.count.notes: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag
        .action("t", _.aware.withCounting.withTiming)
        .retry(IO(i += 1))
        .logOutput(_ => "aware.time.count.notes".asJson)
        .run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))
  }

  test("notice.time.count") {
    print("notice.time.count: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag.action("t", _.notice.withCounting.withTiming).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))
  }

  test("notice.time.count.notes") {
    print("notice.time.count.notes: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag
        .action("t", _.notice.withCounting.withTiming)
        .retry(IO(i += 1))
        .logOutput(_ => "notice.time.count.notes".asJson)
        .run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))
  }

  test("silent.time") {
    print("silent.time: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag.action("t", _.silent.withTiming.withoutCounting).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))
  }

  test("aware.time") {
    print("aware.time: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag.action("t", _.aware.withTiming.withoutCounting).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }
  test("aware.time.notes") {
    print("aware.time.notes: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag
        .action("t", _.aware.withTiming.withoutCounting)
        .retry(IO(i += 1))
        .logOutput(_ => "aware.time.notes".asJson)
        .run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }
  test("notice.time") {
    print("notice.time: ")
    var i = 0
    service.eventStream { ag =>
      val ts =
        ag.action("t", _.notice.withTiming.withoutCounting).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }

  test("notice.time.notes") {
    print("notice.time.notes: ")
    var i = 0
    service.eventStream { ag =>
      val ts =
        ag.action("t", _.notice.withTiming.withoutCounting)
          .retry(IO(i += 1))
          .logOutput(_ => "notice.time.notes".asJson)
          .run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }

  test("silent.counting") {
    print("silent.count: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag.action("t", _.silent.withoutTiming.withCounting).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }

  test("aware.counting") {
    print("aware.count: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag.action("t", _.aware.withoutTiming.withCounting).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }

  test("aware.counting.notes") {
    print("aware.count.notes: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag
        .action("t", _.aware.withoutTiming.withCounting)
        .retry(IO(i += 1))
        .logOutput(_ => "aware.counting.notes".asJson)
        .run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }

  test("notice.counting") {
    print("notice.count: ")
    var i = 0
    service.eventStream { ag =>
      val ts =
        ag.action("t", _.notice.withoutTiming.withCounting).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }

  test("notice.counting.notes") {
    print("notice.count.notes: ")
    var i = 0
    service.eventStream { ag =>
      val ts =
        ag.action("t", _.notice.withoutTiming.withCounting)
          .retry(IO(i += 1))
          .logOutput(_ => "notice.counting.notes".asJson)
          .run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }

  test("silent") {
    print("silent: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag.action("t", _.silent.withoutTiming.withoutCounting).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }

  test("aware") {
    print("aware: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag.action("t", _.aware.withoutTiming.withoutCounting).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }
  test("aware.notes") {
    print("aware.notes: ")
    var i: Int = 0
    service.eventStream { ag =>
      val ts = ag
        .action("t", _.aware.withoutTiming.withoutCounting)
        .retry(IO(i += 1))
        .logOutput(_ => "aware.notes".asJson)
        .run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }
  test("notice") {
    var i = 0
    print("notice: ")
    service.eventStream { ag =>
      val ts =
        ag.action("t", _.notice.withoutTiming.withoutCounting).retry(IO(i += 1)).run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }

  test("notice.notes") {
    var i = 0
    print("notice.notes: ")
    service.eventStream { ag =>
      val ts =
        ag.action("t", _.notice.withoutTiming.withoutCounting)
          .retry(IO(i += 1))
          .logOutput(_ => "notice.notes".asJson)
          .run
      ts.foreverM.timeout(take).attempt
    }.compile.drain.unsafeRunSync()
    println(speed(i))

  }

}
