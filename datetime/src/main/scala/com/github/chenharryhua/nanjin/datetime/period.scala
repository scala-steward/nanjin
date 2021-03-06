package com.github.chenharryhua.nanjin.datetime

import cats.data.{NonEmptyList, Validated}

import java.time.Period
import scala.util.Try

object period {
  import fastparse.NoWhitespace._
  import fastparse._

  private def number[_: P]: P[Int] = P(CharIn("0-9").rep(1).!.map(_.toInt))
  private def year[_: P]: P[Int]   = P(number ~ " ".rep ~ P("years" | "year"))
  private def month[_: P]: P[Int]  = P(number ~ " ".rep ~ P("months" | "month"))
  private def day[_: P]: P[Int]    = P(number ~ " ".rep ~ P("days" | "day"))

  private def ymd[_: P]: P[Period] =
    P(year.? ~ " ".rep ~ month.? ~ " ".rep ~ day.? ~ End).map { case (y, m, d) =>
      Period.of(y.getOrElse(0), m.getOrElse(0), d.getOrElse(0))
    }

  private def homebrew(str: String): Validated[NonEmptyList[String], Period] =
    parse(str, ymd(_)) match {
      case Parsed.Success(v, _)    => Validated.valid(v)
      case Parsed.Failure(_, _, _) => Validated.invalid(NonEmptyList.one(str))
    }

  private def standard(str: String): Validated[NonEmptyList[String], Period] =
    Validated.fromTry(Try(Period.parse(str))).leftMap(e => NonEmptyList.one(e.getMessage))

  def apply(str: String): Validated[NonEmptyList[String], Period] =
    standard(str).orElse(homebrew(str))
}
