package com.github.chenharryhua.nanjin.spark.persist

import com.github.chenharryhua.nanjin.messages.kafka.codec.NJAvroCodec
import com.github.chenharryhua.nanjin.spark.AvroTypedEncoder
import com.github.chenharryhua.nanjin.spark.injection.*
import frameless.TypedEncoder
import io.circe.Codec
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveCodec
import io.circe.shapes.*
import shapeless.{:+:, CNil}

sealed trait CaseObjectCop

object CaseObjectCop {
  case object International extends CaseObjectCop
  case object Domestic extends CaseObjectCop
}

object EnumCoproduct extends Enumeration {
  val International, Domestic = Value
}

object CoproductCop {
  case class International()
  case class Domestic()

  type Cop = International :+: Domestic :+: CNil
}

final case class CoCop(index: Int, cop: CaseObjectCop)

object CoCop {
  val avroCodec: NJAvroCodec[CoCop] = NJAvroCodec[CoCop]

  implicit val circe: Codec[CoCop] = deriveCodec[CoCop]
  import frameless.TypedEncoder.injections.*
  implicit val te: TypedEncoder[CoCop] = shapeless.cachedImplicit
  val ate: AvroTypedEncoder[CoCop]     = AvroTypedEncoder(te, avroCodec)
  // won't compile
  // implicit val row   = RowEncoder[CoCop]
}
final case class EmCop(index: Int, cop: EnumCoproduct.Value)

object EmCop {
  val avroCodec: NJAvroCodec[EmCop]    = NJAvroCodec[EmCop]
  implicit val te: TypedEncoder[EmCop] = shapeless.cachedImplicit
  val ate: AvroTypedEncoder[EmCop]     = AvroTypedEncoder(te, avroCodec)
  implicit val circe: Codec[EmCop]     = deriveCodec[EmCop]

  // won't compile
  // implicit val row = RowEncoder[EmCop]
}

final case class CpCop(index: Int, cop: CoproductCop.Cop)

object CpCop {
  val avroCodec: NJAvroCodec[CpCop] = NJAvroCodec[CpCop]
  implicit val circe: Codec[CpCop]  = deriveCodec[CpCop]
  import frameless.TypedEncoder.injections.*
  implicit val te: TypedEncoder[CpCop] = shapeless.cachedImplicit
  val ate: AvroTypedEncoder[CpCop]     = AvroTypedEncoder(te, avroCodec)
  // won't compile
  // implicit val row = RowEncoder[CpCop]
}
