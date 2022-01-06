package mtest.guard

import cats.Eq
import cats.effect.IO
import cats.laws.discipline.eq.*
import cats.laws.discipline.{ExhaustiveCheck, MonadTests}
import com.github.chenharryhua.nanjin.guard.TaskGuard
import com.github.chenharryhua.nanjin.guard.event.*
import com.github.chenharryhua.nanjin.guard.service.ServiceGuard
import com.github.chenharryhua.nanjin.guard.translators.Translator
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Gen}

import java.time.Instant
import java.util.UUID

object gendata {
  val service: ServiceGuard[IO] = TaskGuard[IO]("monad").service("tailrecM")

  implicit val exhaustiveCheck: ExhaustiveCheck[NJEvent] =
    ExhaustiveCheck.instance(
      List(ServiceStart(ServiceStatus.Up(UUID.randomUUID(), Instant.now()), Instant.now(), service.serviceParams)))

  implicit def translatorEq: Eq[Translator[Option, Int]] =
    Eq.by[Translator[Option, Int], NJEvent => Option[Option[Int]]](_.translate)

  implicit val arbiTranslator: Arbitrary[Translator[Option, Int]] = Arbitrary(
    Gen.const(
      Translator
        .empty[Option, Int]
        .withServiceStart(_ => 1)
        .withServiceStop(_ => 2)
        .withServicePanic(_ => 3)
        .withMetricsReport(_ => 4)
        .withMetricsReset(_ => 5)
        .withInstantAlert(_ => 6)
        .withPassThrough(_ => 7)
        .withActionStart(_ => 8)
        .withActionFail(_ => 9)
        .withActionSucc(_ => 10)
        .withActionRetry(_ => 11)))

  val add: Int => Int = _ + 1

  implicit val arbiAtoB: Arbitrary[Translator[Option, Int => Int]] = Arbitrary(
    Gen.const(
      Translator
        .empty[Option, Int => Int]
        .withServiceStart(_ => add)
        .withServiceStop(_ => add)
        .withServicePanic(_ => add)
        .withMetricsReport(_ => add)
        .withMetricsReset(_ => add)
        .withInstantAlert(_ => add)
        .withPassThrough(_ => add)
        .withActionStart(_ => add)
        .withActionFail(_ => add)
        .withActionSucc(_ => add)
        .withActionRetry(_ => add)))

  implicit val eqAbc: Eq[Translator[Option, (Int, Int, Int)]] =
    (x: Translator[Option, (Int, Int, Int)], y: Translator[Option, (Int, Int, Int)]) => true
}

class TranslatorMonadTest extends DisciplineSuite {
  import gendata.*
  // just check tailRecM stack safety
  checkAll("Translator.MonadLaws", MonadTests[Translator[Option, *]].monad[Int, Int, Int])

}
