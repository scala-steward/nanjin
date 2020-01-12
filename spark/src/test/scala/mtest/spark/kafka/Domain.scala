package mtest.spark.kafka

import java.time.{LocalDate, LocalDateTime}

import cats.Show
import frameless.Injection
import io.circe.generic.JsonCodec
import cats.implicits._
import cats.derived.auto.show._
import java.time.Instant

final case class EmbeddedForTaskSerializable(f: Int, g: String)

final case class ForTaskSerializable(
  a: Int,
  b: String,
  c: LocalDate,
  d: Instant,
  e: EmbeddedForTaskSerializable)

@JsonCodec case class Payment(
  id: String,
  time: String,
  amount: BigDecimal,
  currency: String,
  creditCardId: String,
  merchantId: Long)

sealed trait Colorish

object Colorish {

  implicit val colorInjection: Injection[Colorish, String] =
    new Injection[Colorish, String] {

      override def apply(a: Colorish): String = a match {
        case Red   => "red"
        case Blue  => "blue"
        case Green => "green"
      }

      override def invert(b: String): Colorish = b match {
        case "red"   => Red
        case "blue"  => Blue
        case "green" => Green
      }
    }
  case object Red extends Colorish
  case object Green extends Colorish
  case object Blue extends Colorish
}

final case class Pencil(name: String, color: Colorish)

case class FirstStream(name: String, age: Int)
case class SecondStream(name: String, score: Int)
