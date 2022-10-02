package mtest.guard

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.catsSyntaxMonadErrorRethrow
import com.github.chenharryhua.nanjin.guard.*
import com.github.chenharryhua.nanjin.guard.event.*
import com.github.chenharryhua.nanjin.guard.event.NJEvent.*
import com.github.chenharryhua.nanjin.guard.service.ServiceGuard
import eu.timepit.refined.auto.*
import io.circe.parser.decode
import org.scalatest.funsuite.AnyFunSuite
import io.circe.syntax.*

import scala.concurrent.duration.*

class CancellationTest extends AnyFunSuite {
  val serviceGuard: ServiceGuard[IO] =
    TaskGuard[IO]("retry-guard").service("retry-test").updateConfig(_.withConstantDelay(1.seconds))

  test("1.cancellation - canceled actions are failed actions") {
    val Vector(a, b, c, d) = serviceGuard
      .updateConfig(_.withConstantDelay(1.hour))
      .eventStream(ag =>
        ag.action(_.withConstantDelay(1.second, 3).notice).retry(IO(1) <* IO.canceled).run("canceled"))
      .map(_.asJson.noSpaces)
      .evalMap(e => IO(decode[NJEvent](e)).rethrow)
      .compile
      .toVector
      .unsafeRunSync()
    assert(a.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ActionStart])
    assert(c.isInstanceOf[ActionFail])
    assert(d.isInstanceOf[ServiceStop])
    assert(d.asInstanceOf[ServiceStop].cause.isInstanceOf[ServiceStopCause.ByCancelation.type])

  }

  test("2.cancellation - can be canceled externally") {
    val Vector(s, b, c) = serviceGuard
      .updateConfig(_.withConstantDelay(1.hour))
      .eventStream { ag =>
        val a1 = ag.action(_.silent).retry(IO.never[Int]).run("never")
        IO.parSequenceN(2)(List(IO.sleep(2.second) >> IO.canceled, a1))
      }
      .map(_.asJson.noSpaces)
      .evalMap(e => IO(decode[NJEvent](e)).rethrow)
      .compile
      .toVector
      .unsafeRunSync()
    assert(s.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ActionFail])
    assert(c.isInstanceOf[ServiceStop])
  }

  test("3.canceled by external exception") {
    val Vector(s, b, c) = serviceGuard
      .updateConfig(_.withConstantDelay(1.hour))
      .eventStream { ag =>
        val a1 = ag.action(_.trivial).retry(IO.never[Int]).run("never")
        IO.parSequenceN(2)(List(IO.sleep(1.second) >> IO.raiseError(new Exception), a1))
      }
      .map(_.asJson.noSpaces)
      .evalMap(e => IO(decode[NJEvent](e)).rethrow)
      .interruptAfter(3.seconds)
      .compile
      .toVector
      .unsafeRunSync()
    assert(s.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ActionFail])
    assert(c.isInstanceOf[ServicePanic])

  }

  test("4.cancellation should propagate in right order") {
    val Vector(a, b, c, d) = serviceGuard
      .updateConfig(_.withConstantDelay(1.hour))
      .eventStream { ag =>
        val a1 = ag.action(_.silent).retry(IO.never[Int]).run("one/two/inner")
        ag.action(_.silent)
          .retry(IO.parSequenceN(2)(List(IO.sleep(2.second) >> IO.canceled, a1)))
          .run("one/two/three/outer")
      }
      .map(_.asJson.noSpaces)
      .evalMap(e => IO(decode[NJEvent](e)).rethrow)
      .interruptAfter(10.second)
      .compile
      .toVector
      .unsafeRunSync()
    assert(a.isInstanceOf[ServiceStart])
    assert(b.asInstanceOf[ActionFail].actionInfo.digested.metricRepr == "[one/two/inner][89f90a0c]")
    assert(c.asInstanceOf[ActionFail].actionInfo.digested.metricRepr == "[one/two/three/outer][59553dec]")
    assert(d.isInstanceOf[ServiceStop])
  }

  test("5.cancellation - sequentially - cancel after two succ") {
    val Vector(s, a, b, c, d, e) = serviceGuard
      .updateConfig(_.withConstantDelay(1.hour))
      .eventStream { ag =>
        ag.action(_.notice).retry(IO(1)).run("a1") >>
          ag.action(_.notice).retry(IO(1)).run("a2") >>
          IO.canceled >>
          ag.action(_.notice).retry(IO(1)).run("a3")
      }
      .map(_.asJson.noSpaces)
      .evalMap(e => IO(decode[NJEvent](e)).rethrow)
      .compile
      .toVector
      .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStart])
    assert(a.isInstanceOf[ActionStart])
    assert(b.asInstanceOf[ActionSucc].actionInfo.digested.metricRepr == "[a1][6f340f3f]")
    assert(c.isInstanceOf[ActionStart])
    assert(d.asInstanceOf[ActionSucc].actionInfo.digested.metricRepr == "[a2][56199b40]")
    assert(e.isInstanceOf[ServiceStop])

  }

  test("6.cancellation - sequentially - no chance to cancel") {
    val Vector(s, a, b, c, d, e, f) = serviceGuard
      .updateConfig(_.withConstantDelay(1.hour))
      .eventStream { ag =>
        ag.action(_.notice).retry(IO(1)).run("a1") >>
          ag.action(_.notice.withConstantDelay(1.second, 1)).retry(IO.raiseError(new Exception)).run("a2") >>
          IO.canceled >> // no chance to cancel since a2 never success
          ag.action(_.notice).retry(IO(1)).run("a3")
      }
      .map(_.asJson.noSpaces)
      .evalMap(e => IO(decode[NJEvent](e)).rethrow)
      .interruptAfter(5.second)
      .compile
      .toVector
      .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStart])
    assert(a.isInstanceOf[ActionStart])
    assert(b.asInstanceOf[ActionSucc].actionInfo.digested.metricRepr == "[a1][6f340f3f]")
    assert(c.isInstanceOf[ActionStart])
    assert(d.asInstanceOf[ActionRetry].actionInfo.digested.metricRepr == "[a2][56199b40]")
    assert(e.asInstanceOf[ActionFail].actionInfo.digested.metricRepr == "[a2][56199b40]")
    assert(f.isInstanceOf[ServicePanic])

  }

  test("7.cancellation - parallel") {
    val v =
      serviceGuard
        .updateConfig(_.withConstantDelay(1.hour))
        .eventStream { ag =>
          val a1 = ag.action(_.notice).retry(IO.sleep(1.second) >> IO(1)).run("succ-1")
          val a2 = ag
            .action(_.notice.withConstantDelay(1.second, 3))
            .retry(IO.raiseError[Int](new Exception))
            .run("fail-2")
          val a3 = ag.action(_.notice).retry(IO.never[Int]).run("cancel-3")
          ag.action(_.notice.withConstantDelay(1.second, 1))
            .retry(IO.parSequenceN(5)(List(a1, a2, a3)))
            .run("supervisor")
        }
        .map(_.asJson.noSpaces)
        .evalMap(e => IO(decode[NJEvent](e)).rethrow)
        .interruptAfter(10.second)
        .compile
        .toVector
        .unsafeRunSync()

    assert(v(0).isInstanceOf[ServiceStart])
    assert(v(1).isInstanceOf[ActionStart])
    assert(v(2).isInstanceOf[ActionStart])
    assert(v(3).isInstanceOf[ActionStart])
    assert(v(4).isInstanceOf[ActionStart])

    assert(v(5).isInstanceOf[ActionRetry]) // a2
    assert(v(6).isInstanceOf[ActionSucc] || v(6).isInstanceOf[ActionRetry]) // a1
    assert(v(7).isInstanceOf[ActionRetry] || v(7).isInstanceOf[ActionSucc]) // a2
    assert(v(8).isInstanceOf[ActionRetry]) // a2
    assert(v(9).isInstanceOf[ActionFail]) // a2 failed
    assert(v(10).isInstanceOf[ActionFail]) // a3 cancelled

    assert(v(11).isInstanceOf[ActionRetry]) // supervisor
    assert(v(12).isInstanceOf[ActionStart])
    assert(v(13).isInstanceOf[ActionStart])
    assert(v(14).isInstanceOf[ActionStart])

    assert(v(15).isInstanceOf[ActionRetry] || v(15).isInstanceOf[ActionSucc]) // a1 or a2
    assert(v(16).isInstanceOf[ActionSucc] || v(16).isInstanceOf[ActionRetry]) // a1 or a2
    assert(v(17).isInstanceOf[ActionRetry] || v(17).isInstanceOf[ActionSucc]) // a1 or a2
    assert(v(18).isInstanceOf[ActionRetry]) // a2
    assert(v(19).isInstanceOf[ActionFail]) // a2 failed
    assert(v(20).isInstanceOf[ActionFail]) // a3 cancelled
    assert(v(21).isInstanceOf[ActionFail]) // supervisor
    assert(v(22).isInstanceOf[ServicePanic])
  }

  test("8.cancellation - cancel in middle of retrying") {
    val Vector(s, a, b, c, d, e) = serviceGuard
      .updateConfig(_.withConstantDelay(1.hour))
      .eventStream { ag =>
        val a1 = ag
          .action(_.notice.withConstantDelay(2.second, 100))
          .retry(IO.raiseError[Int](new Exception))
          .run("exception")
        IO.parSequenceN(2)(List(IO.sleep(3.second) >> IO.canceled, a1))
      }
      .map(_.asJson.noSpaces)
      .evalMap(e => IO(decode[NJEvent](e)).rethrow)
      .compile
      .toVector
      .unsafeRunSync()
    assert(s.isInstanceOf[ServiceStart])
    assert(a.isInstanceOf[ActionStart])
    assert(b.isInstanceOf[ActionRetry])
    assert(c.isInstanceOf[ActionRetry])
    assert(d.isInstanceOf[ActionFail])
    assert(e.isInstanceOf[ServiceStop])
  }

  test("9.cancellation - wrapped within uncancelable") {
    val Vector(s, b, c, d, e, f) = serviceGuard
      .updateConfig(_.withConstantDelay(1.hour))
      .eventStream { ag =>
        val a1 = ag
          .action(_.withConstantDelay(1.second, 3))
          .retry(IO.raiseError[Int](new Exception))
          .run("exception")
        IO.parSequenceN(2)(List(IO.sleep(2.second) >> IO.canceled, IO.uncancelable(_ => a1)))
      }
      .map(_.asJson.noSpaces)
      .evalMap(e => IO(decode[NJEvent](e)).rethrow)
      .interruptAfter(5.seconds)
      .compile
      .toVector
      .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ActionRetry])
    assert(c.isInstanceOf[ActionRetry])
    assert(d.isInstanceOf[ActionRetry])
    assert(e.isInstanceOf[ActionFail])
    assert(f.isInstanceOf[ServicePanic])
  }
  test("10.cancellation - never can be canceled") {
    var i = 0
    serviceGuard
      .updateConfig(_.withConstantDelay(1.hour))
      .eventStream(_ => IO.never.onCancel(IO { i = 1 }))
      .map(_.asJson.noSpaces)
      .evalMap(e => IO(decode[NJEvent](e)).rethrow)
      .interruptAfter(2.seconds)
      .compile
      .drain
      .unsafeRunSync()
    assert(i == 1)
  }
}
