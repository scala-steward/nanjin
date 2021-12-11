package mtest.guard

import cats.data.Chain
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.aws.SimpleNotificationService
import com.github.chenharryhua.nanjin.guard.TaskGuard
import com.github.chenharryhua.nanjin.guard.event.*
import com.github.chenharryhua.nanjin.guard.observers.{logging, slack}
import fs2.Chunk
import io.circe.parser.decode
import org.scalatest.funsuite.AnyFunSuite

import java.time.Duration as JavaDuration
import scala.concurrent.duration.*

class QuasiSuccTest extends AnyFunSuite {
  val guard = TaskGuard[IO]("qusai succ app").service("quasi")

  def f(a: Int): IO[Int] = IO(100 / a)

  test("quasi all succ - list") {
    val Vector(s, a, b, c) =
      guard
        .eventStream(action => action.span("all-good").notice.quasi(List(1, 2, 3))(f).seqRun)
        .compile
        .toVector
        .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStarted])
    assert(a.isInstanceOf[ActionStart])
    assert(b.asInstanceOf[ActionQuasiSucced].numSucc == 3)
    assert(b.asInstanceOf[ActionQuasiSucced].errors.isEmpty)
    assert(c.isInstanceOf[ServiceStopped])
  }

  test("quasi all fail - chunk") {
    val Vector(s, a, b, c) = guard
      .eventStream(action => action.span("all-fail").notice.quasi(Chunk(0, 0, 0))(f).seqRun)
      .compile
      .toVector
      .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStarted])
    assert(a.isInstanceOf[ActionStart])
    assert(b.asInstanceOf[ActionQuasiSucced].numSucc == 0)
    assert(b.asInstanceOf[ActionQuasiSucced].errors.size == 3)
    assert(c.isInstanceOf[ServiceStopped])
  }

  test("quasi partial succ - chain") {
    val sns = SimpleNotificationService.fake[IO]
    val Vector(s, a, b, c) =
      guard
        .eventStream(action => action.span("partial-good").notice.quasi(Chain(2, 0, 1))(f).seqRun)
        .map(e => decode[NJEvent](e.asJson.noSpaces).toOption)
        .unNone
        .through(slack[IO](sns))
        .evalTap(logging[IO](_.show))
        .evalTap(logging[IO](_.asJson.noSpaces))
        .compile
        .toVector
        .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStarted])
    assert(a.isInstanceOf[ActionStart])
    assert(b.asInstanceOf[ActionQuasiSucced].numSucc == 2)
    assert(b.asInstanceOf[ActionQuasiSucced].errors.size == 1)
    assert(c.isInstanceOf[ServiceStopped])
  }

  test("quasi partial succ - vector") {
    def f(a: Int): IO[Int] = IO.sleep(1.second) >> IO(100 / a)
    val Vector(s, a, b, c) =
      guard
        .eventStream(action => action.span("partial-good").notice.quasi(Vector(0, 0, 1, 1))(f).seqRun)
        .compile
        .toVector
        .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStarted])
    assert(a.isInstanceOf[ActionStart])
    val succ = b.asInstanceOf[ActionQuasiSucced]
    assert(succ.numSucc == 2)
    assert(succ.errors.size == 2)
    assert(JavaDuration.between(succ.actionInfo.launchTime, succ.timestamp).abs.getSeconds > 3)
    assert(c.isInstanceOf[ServiceStopped])
  }

  test("quasi parallel - par") {
    def f(a: Int): IO[Int] = IO.sleep(1.second) >> IO(100 / a)
    val Vector(s, a, b, c) =
      guard
        .eventStream(action => action.span("parallel").notice.quasi(Vector(0, 0, 0, 1, 1, 1))(f).parRun)
        .compile
        .toVector
        .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStarted])
    assert(a.isInstanceOf[ActionStart])
    val succ = b.asInstanceOf[ActionQuasiSucced]
    assert(succ.numSucc == 3)
    assert(succ.errors.size == 3)
    assert(JavaDuration.between(succ.actionInfo.launchTime, succ.timestamp).abs.getSeconds < 2)
    assert(c.isInstanceOf[ServiceStopped])
  }

  test("quasi parallel - parN") {
    def f(a: Int): IO[Int] = IO.sleep(1.second) >> IO(100 / a)
    val Vector(s, a, b, c) =
      guard
        .eventStream(action => action.span("parallel").notice.quasi(Vector(0, 0, 0, 1, 1, 1))(f).parRun(3))
        .compile
        .toVector
        .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStarted])
    assert(a.isInstanceOf[ActionStart])
    val succ = b.asInstanceOf[ActionQuasiSucced]
    assert(succ.numSucc == 3)
    assert(succ.errors.size == 3)
    assert(JavaDuration.between(succ.actionInfo.launchTime, succ.timestamp).abs.getSeconds < 3)
    assert(c.isInstanceOf[ServiceStopped])
  }

  test("quasi pure actions") {
    def f(a: Int): IO[Unit] = IO.sleep(1.second) <* IO(100 / a)
    val Vector(s, a, b, c) =
      guard
        .eventStream(action => action.span("pure actions").notice.quasi(f(0), f(0), f(0), f(1), f(1), f(1)).parRun)
        .compile
        .toVector
        .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStarted])
    assert(a.isInstanceOf[ActionStart])
    val succ = b.asInstanceOf[ActionQuasiSucced]
    assert(succ.numSucc == 3)
    assert(succ.errors.size == 3)
    assert(JavaDuration.between(succ.actionInfo.launchTime, succ.timestamp).abs.getSeconds < 2)
    assert(c.isInstanceOf[ServiceStopped])
  }

  test("quasi cancallation - internal") {
    def f(a: Int): IO[Unit] = IO.sleep(1.second) <* IO(100 / a)
    val Vector(s, a, b, c) =
      guard
        .eventStream(action =>
          action.span("internal-cancel").notice.quasi(f(0), IO.sleep(1.second) >> IO.canceled, f(1), f(2)).seqRun)
        .interruptAfter(5.seconds)
        .compile
        .toVector
        .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStarted])
    assert(a.isInstanceOf[ActionStart])
    assert(b.asInstanceOf[ActionFailed].error.throwable.get.getMessage == "action was canceled internally")
    assert(c.isInstanceOf[ServicePanic])
  }

  test("quasi cancalation - external cancelation") {
    def f(a: Int): IO[Unit] = IO.sleep(1.second) <* IO(100 / a)
    val Vector(s, a, b, c) =
      guard.eventStream { action =>
        val a1 =
          action
            .span("external-cancel")
            .notice
            .updateConfig(_.withConstantDelay(1.second))
            .quasi(Vector(f(0), f(1)))
            .parRun(2)
        List(a1, IO.canceled.delayBy(3.second)).parSequence_
      }.compile.toVector.unsafeRunSync()

    assert(s.isInstanceOf[ServiceStarted])
    assert(a.isInstanceOf[ActionStart])
    assert(b.isInstanceOf[ActionQuasiSucced])
    assert(c.isInstanceOf[ServiceStopped])
  }

  test("quasi multi-layers seq") {
    val Vector(s, a, b, c, d, e, f, g, h, i, j, k, l) =
      guard.eventStream { action =>
        val a1 = action.span("compute1").notice.run(IO(1))
        val a2 = action
          .span("exception")
          .notice
          .updateConfig(_.withConstantDelay(1.second).withMaxRetries(3))
          .retry(IO.raiseError[Int](new Exception))
          .run
        val a3 = action.span("compute2").notice.run(IO(2))
        action.span("quasi").notice.quasi(a1, a2, a3).seqRun
      }.compile.toVector.unsafeRunSync()

    assert(s.isInstanceOf[ServiceStarted])
    assert(a.asInstanceOf[ActionStart].actionParams.actionName == "quasi")
    assert(b.asInstanceOf[ActionStart].actionParams.actionName == "compute1")
    assert(c.asInstanceOf[ActionSucced].actionParams.actionName == "compute1")
    assert(d.isInstanceOf[ActionStart])
    assert(e.isInstanceOf[ActionRetrying])
    assert(f.isInstanceOf[ActionRetrying])
    assert(g.isInstanceOf[ActionRetrying])
    assert(h.isInstanceOf[ActionFailed])
    assert(i.isInstanceOf[ActionStart])
    assert(j.asInstanceOf[ActionSucced].actionParams.actionName == "compute2")
    assert(k.isInstanceOf[ActionQuasiSucced])
    assert(l.isInstanceOf[ServiceStopped])
  }

  test("quasi multi-layers - par") {
    val Vector(s, a, b, c, e, f, g, h, j, k, l) =
      guard.eventStream { action =>
        val a1 = action.span("compute1").trivial.retry(IO.sleep(5.seconds) >> IO(1)).run
        val a2 =
          action
            .span("exception")
            .notice
            .max(3)
            .updateConfig(_.withConstantDelay(1.second))
            .retry(IO.raiseError[Int](new Exception))
            .run
        val a3 = action.span("compute2").notice.retry(IO.sleep(5.seconds) >> IO(2)).run
        action.span("quasi").notice.quasi(a1, a2, a3).parRun(3)
      }.compile.toVector.unsafeRunSync()

    assert(s.isInstanceOf[ServiceStarted])
    assert(a.isInstanceOf[ActionStart])
    assert(b.isInstanceOf[ActionStart])
    assert(c.isInstanceOf[ActionStart])
    assert(e.isInstanceOf[ActionRetrying])
    assert(f.isInstanceOf[ActionRetrying])
    assert(g.isInstanceOf[ActionRetrying])
    assert(h.isInstanceOf[ActionFailed])
    assert(j.isInstanceOf[ActionSucced])
    assert(k.isInstanceOf[ActionQuasiSucced])
    assert(l.isInstanceOf[ServiceStopped])
  }
}
