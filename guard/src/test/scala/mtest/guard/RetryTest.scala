package mtest.guard

import cats.data.Validated
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.github.chenharryhua.nanjin.guard.*
import com.github.chenharryhua.nanjin.guard.event.NJEvent
import com.github.chenharryhua.nanjin.guard.event.NJEvent.*
import com.github.chenharryhua.nanjin.guard.observers.console
import com.github.chenharryhua.nanjin.guard.service.ServiceGuard
import cron4s.expr.CronExpr
import cron4s.Cron
import eu.timepit.refined.auto.*
import io.circe.parser.decode
import io.circe.syntax.*
import org.scalatest.funsuite.AnyFunSuite
import retry.RetryPolicies

import scala.concurrent.duration.*
import scala.util.Try
import scala.util.control.ControlThrowable

final case class MyException() extends Exception("my exception")

class RetryTest extends AnyFunSuite {

  val serviceGuard: ServiceGuard[IO] =
    TaskGuard[IO]("retry-guard").service("retry test").withRestartPolicy(constant_1second)

  val policy = RetryPolicies.constantDelay[IO](1.seconds).join(RetryPolicies.limitRetries(3))

  test("1.retry - success trivial") {
    val Vector(s, c) = serviceGuard.eventStream { gd =>
      gd.action("t").retry(fun3 _).logOutput((a, _) => a.asJson).withWorthRetry(_ => true).run((1, 1, 1))
    }.evalMap(e => IO(decode[NJEvent](e.asJson.noSpaces)).rethrow).compile.toVector.unsafeRunSync()

    assert(s.isInstanceOf[ServiceStart])
    assert(c.isInstanceOf[ServiceStop])
  }

  test("2.retry - success notice") {
    val Vector(s, a, b, c, d, e, f, g) = serviceGuard.eventStream { gd =>
      val ag =
        gd.action("t", _.notice).retry(fun5 _).logInput.withWorthRetry(_ => true)
      List(1, 2, 3).traverse(i => ag.run((i, i, i, i, i)))
    }.evalMap(e => IO(decode[NJEvent](e.asJson.noSpaces)).rethrow).compile.toVector.unsafeRunSync()

    assert(s.isInstanceOf[ServiceStart])
    assert(a.isInstanceOf[ActionStart])
    assert(b.isInstanceOf[ActionSucc])
    assert(c.isInstanceOf[ActionStart])
    assert(d.isInstanceOf[ActionSucc])
    assert(e.isInstanceOf[ActionStart])
    assert(f.isInstanceOf[ActionSucc])
    assert(g.isInstanceOf[ServiceStop])
  }

  test("3.retry - all fail") {
    val policy = RetryPolicies.constantDelay[IO](0.1.seconds).join(RetryPolicies.limitRetries(1))
    val Vector(s, a, b, c, d, e, f, g, h, i, j) = serviceGuard.eventStream { gd =>
      val ag = gd
        .action("t", _.notice)
        .withRetryPolicy(policy)
        .retry((_: Int, _: Int, _: Int) => IO.raiseError[Int](new Exception))
        .logOutput((in, out) => (in._3, out).asJson)
        .logOutput((in, out) => (in, out).asJson)

      List(1, 2, 3).traverse(i => ag.run((i, i, i)).attempt)
    }.evalMap(e => IO(decode[NJEvent](e.asJson.noSpaces)).rethrow).compile.toVector.unsafeRunSync()

    assert(s.isInstanceOf[ServiceStart])
    assert(a.isInstanceOf[ActionStart])
    assert(b.isInstanceOf[ActionRetry])
    assert(c.isInstanceOf[ActionFail])
    assert(d.isInstanceOf[ActionStart])
    assert(e.isInstanceOf[ActionRetry])
    assert(f.isInstanceOf[ActionFail])
    assert(g.isInstanceOf[ActionStart])
    assert(h.isInstanceOf[ActionRetry])
    assert(i.isInstanceOf[ActionFail])
    assert(j.isInstanceOf[ServiceStop])

    assert(b.asInstanceOf[ActionRetry].retriesSoFar == 0)
    assert(e.asInstanceOf[ActionRetry].retriesSoFar == 0)
    assert(h.asInstanceOf[ActionRetry].retriesSoFar == 0)
  }

  test("4.retry - should retry 2 times when operation fail") {
    var i = 0
    val Vector(s, a, b, c, d, e) = serviceGuard.eventStream { gd =>
      gd.action("t", _.notice)
        .withRetryPolicy(policy)
        .retry((_: Int) =>
          IO(if (i < 2) {
            i += 1; throw new Exception
          } else i))
        .logOutput((a, _) => a.asJson)
        .run(1)
    }.evalMap(e => IO(decode[NJEvent](e.asJson.noSpaces)).rethrow).compile.toVector.unsafeRunSync()

    assert(s.isInstanceOf[ServiceStart])
    assert(a.isInstanceOf[ActionStart])
    assert(b.isInstanceOf[ActionRetry])
    assert(c.isInstanceOf[ActionRetry])
    assert(d.isInstanceOf[ActionSucc])
    assert(e.isInstanceOf[ServiceStop])
  }

  test("5.retry - should retry 2 times when operation fail - low") {
    var i = 0
    val Vector(s, b, c, d, e, f) = serviceGuard.eventStream { gd =>
      gd.action("t", _.critical)
        .withRetryPolicy(policy)
        .retry((_: Int) =>
          IO(if (i < 2) {
            i += 1
            throw new Exception
          } else i))
        .logInput(_.asJson)
        .run(1)
    }.compile.toVector.unsafeRunSync()

    assert(s.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ActionStart])
    assert(c.isInstanceOf[ActionRetry])
    assert(d.isInstanceOf[ActionRetry])
    assert(e.isInstanceOf[ActionSucc])
    assert(f.isInstanceOf[ServiceStop])
  }

  test("6.retry - should escalate to up level if retry failed") {
    val policy = RetryPolicies.constantDelay[IO](1.seconds).join(RetryPolicies.limitRetries(3))
    val Vector(s, b, c, d, e, f) = serviceGuard
      .withRestartPolicy(RetryPolicies.alwaysGiveUp)
      .eventStream { gd =>
        gd.action("t")
          .withRetryPolicy(policy)
          .retry((_: Int) => IO.raiseError[Int](new Exception("oops")))
          .logInput
          .run(1)
      }
      .evalMap(e => IO(decode[NJEvent](e.asJson.noSpaces)).rethrow)
      .compile
      .toVector
      .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ActionRetry])
    assert(c.isInstanceOf[ActionRetry])
    assert(d.isInstanceOf[ActionRetry])
    assert(e.isInstanceOf[ActionFail])
    assert(f.isInstanceOf[ServiceStop])

    assert(b.asInstanceOf[ActionRetry].retriesSoFar == 0)
    assert(c.asInstanceOf[ActionRetry].retriesSoFar == 1)
    assert(d.asInstanceOf[ActionRetry].retriesSoFar == 2)
  }

  test("7.retry - Null pointer exception") {
    val a :: b :: c :: d :: e :: f :: _ = serviceGuard
      .withRestartPolicy(RetryPolicies.alwaysGiveUp)
      .eventStream(ag =>
        ag.action("t")
          .withRetryPolicy(policy)
          .retry(IO.raiseError[Int](new NullPointerException))
          .logOutput
          .run)
      .evalMap(e => IO(decode[NJEvent](e.asJson.noSpaces)).rethrow)
      .compile
      .toList
      .unsafeRunSync()
    assert(a.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ActionRetry])
    assert(c.isInstanceOf[ActionRetry])
    assert(d.isInstanceOf[ActionRetry])
    assert(e.isInstanceOf[ActionFail])
    assert(f.isInstanceOf[ServiceStop])
  }

  test("8.retry - predicate - should retry") {
    val policy = RetryPolicies.constantDelay[IO](0.1.seconds).join(RetryPolicies.limitRetries(3))
    val Vector(s, b, c, d, e, f) = serviceGuard
      .withRestartPolicy(RetryPolicies.alwaysGiveUp)
      .eventStream { gd =>
        gd.action("t")
          .withRetryPolicy(policy)
          .retry(IO.raiseError(MyException()))
          .withWorthRetry(_.isInstanceOf[MyException])
          .run
      }
      .evalMap(e => IO(decode[NJEvent](e.asJson.noSpaces)).rethrow)
      .compile
      .toVector
      .unsafeRunSync()

    assert(s.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ActionRetry])
    assert(c.isInstanceOf[ActionRetry])
    assert(d.isInstanceOf[ActionRetry])
    assert(e.isInstanceOf[ActionFail])
    assert(f.isInstanceOf[ServiceStop])
  }

  test("9.retry - isWorthRetry - should not retry") {
    val policy = RetryPolicies.constantDelay[IO](0.1.seconds).join(RetryPolicies.limitRetries(3))
    val Vector(s, a, b, c) = serviceGuard
      .withRestartPolicy(constant_1hour)
      .eventStream { gd =>
        gd.action("t", _.notice)
          .withRetryPolicy(policy)
          .retry(IO.raiseError(new Exception))
          .withWorthRetry(_.isInstanceOf[MyException])
          .run
      }
      .evalMap(e => IO(decode[NJEvent](e.asJson.noSpaces)).rethrow)
      .interruptAfter(5.seconds)
      .compile
      .toVector
      .unsafeRunSync()
    assert(s.isInstanceOf[ServiceStart])
    assert(a.isInstanceOf[ActionStart])
    assert(b.isInstanceOf[ActionFail])
    assert(c.isInstanceOf[ServicePanic])
  }

  test("10.quasi syntax") {
    serviceGuard.eventStream { ag =>
      val builder = ag.action("t", _.notice)
      builder.quasi(3)(IO("a"), IO("b")).run >>
        builder.quasi(3, List(IO("a"), IO("b"))).run >>
        builder.quasi(List(IO("a"), IO("b"))).run >>
        builder.quasi(IO("a"), IO("b")).run >>
        builder.quasi(IO.print("a"), IO.print("b")).run >>
        builder.quasi(3)(IO.print("a"), IO.print("b")).run
    }
  }

  test("11.cron policy") {
    val secondly: CronExpr = Cron.unsafeParse("0-59 * * ? * *")
    val policy             = policies.cronBackoff[IO](secondly).join(RetryPolicies.limitRetries(3))
    val List(a, b, c, d, e, f, g) = serviceGuard
      .withRestartPolicy(RetryPolicies.alwaysGiveUp)
      .eventStream(
        _.action("cron", _.notice).withRetryPolicy(policy).retry(IO.raiseError(new Exception("oops"))).run)
      .evalTap(console.simple[IO])
      .compile
      .toList
      .unsafeRunSync()

    assert(a.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ActionStart])
    assert(c.isInstanceOf[ActionRetry])
    assert(d.isInstanceOf[ActionRetry])
    assert(e.isInstanceOf[ActionRetry])
    assert(f.isInstanceOf[ActionFail])
    assert(g.isInstanceOf[ServiceStop])

  }

  test("12.run synatx") {
    serviceGuard.eventStream { ag =>
      val a0 = ag.action("a0").retry(unit_fun).run
      val a1 = ag.action("a1").retry(fun1 _).run(1)
      val a2 = ag.action("a2").retry(fun2 _).run((1, 2))
      val a3 = ag.action("a3").retry(fun3 _).run((1, 2, 3))
      val a4 = ag.action("a4").retry(fun4 _).run((1, 2, 3, 4))
      val a5 = ag.action("a5").retry(fun5 _).run((1, 2, 3, 4, 5))
      val f0 = ag.action("f0").retryFuture(fun0fut).run
      val f1 = ag.action("f1").retryFuture(fun1fut _).run(1)
      val f2 = ag.action("f2").retryFuture(fun2fut _).run((1, 2))
      val f3 = ag.action("f3").retryFuture(fun3fut _).run((1, 2, 3))
      val f4 = ag.action("f4").retryFuture(fun4fut _).run((1, 2, 3, 4))
      val f5 = ag.action("f5").retryFuture(fun5fut _).run((1, 2, 3, 4, 5))
      val e1 = ag.action("e1").retry(Try(1)).run
      val e2 = ag.action("e2").retry(Some(1)).run
      val e3 = ag.action("e3").retry(Right(1)).run
      val e4 = ag.action("e4").retry(Validated.Valid(1)).run
      a0 >> a1 >> a2 >> a3 >> a4 >> a5 >> f0 >> f1 >> f2 >> f3 >> f4 >> f5 >> e1 >> e2 >> e3 >> e4
    }.compile.drain.unsafeRunSync()
  }

  test("13.retry - nonStop - should retry") {
    val a :: b :: c :: d :: e :: f :: _ = serviceGuard
      .withRestartPolicy(constant_1second)
      .eventStream(_.nonStop(fs2.Stream(1))) // suppose run forever but...
      .interruptAfter(5.seconds)
      .map(e => decode[NJEvent](e.asJson.noSpaces).toOption)
      .unNone
      .compile
      .toList
      .unsafeRunSync()

    assert(a.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ServicePanic])
    assert(c.isInstanceOf[ServiceStart])
    assert(d.isInstanceOf[ServicePanic])
    assert(e.isInstanceOf[ServiceStart])
    assert(f.isInstanceOf[ServicePanic])
  }

  test("14.should not retry fatal error") {
    val List(a, b, c, d) = serviceGuard
      .withRestartPolicy(RetryPolicies.alwaysGiveUp)
      .eventStream(
        _.action("fatal", _.critical)
          .withRetryPolicy(constant_1second)
          .retry(IO.raiseError(new ControlThrowable("fatal error") {}))
          .run)
      .compile
      .toList
      .unsafeRunSync()

    assert(a.isInstanceOf[ServiceStart])
    assert(b.isInstanceOf[ActionStart])
    assert(c.isInstanceOf[ActionFail])
    assert(d.isInstanceOf[ServiceStop])
  }
}
