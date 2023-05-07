package com.github.chenharryhua.nanjin.guard.config

import enumeratum.values.{CatsOrderValueEnum, IntCirceEnum, IntEnum, IntEnumEntry}
import enumeratum.{CatsEnum, CirceEnum, Enum, EnumEntry}
import io.circe.Json
import retry.RetryPolicy

import java.time.Instant
import java.util.UUID

sealed abstract class PublishStrategy(override val entryName: String) extends EnumEntry with Product

object PublishStrategy
    extends Enum[PublishStrategy] with CirceEnum[PublishStrategy] with CatsEnum[PublishStrategy] {
  override val values: IndexedSeq[PublishStrategy] = findValues

  case object Notice extends PublishStrategy("notice") // publish start and complete event
  case object Aware extends PublishStrategy("aware") // publish complete event
  case object Silent extends PublishStrategy("silent") // publish nothing
}

sealed abstract class Importance(override val value: Int, val entryName: String)
    extends IntEnumEntry with Product

object Importance
    extends CatsOrderValueEnum[Int, Importance] with IntEnum[Importance] with IntCirceEnum[Importance] {
  override val values: IndexedSeq[Importance] = findValues

  case object Critical extends Importance(4, "critical")
  case object Normal extends Importance(3, "normal")
  case object Insignificant extends Importance(2, "insignificant")
  case object Trivial extends Importance(1, "trivial")
}

sealed abstract class AlertLevel(override val value: Int, val entryName: String)
    extends IntEnumEntry with Product

object AlertLevel
    extends CatsOrderValueEnum[Int, AlertLevel] with IntEnum[AlertLevel] with IntCirceEnum[AlertLevel] {
  override val values: IndexedSeq[AlertLevel] = findValues

  case object Error extends AlertLevel(3, "error")
  case object Warn extends AlertLevel(2, "warn")
  case object Info extends AlertLevel(1, "info")
}

final private[guard] case class ServiceName(value: String) extends AnyVal
final private[guard] case class ServiceID(value: UUID) extends AnyVal
final private[guard] case class ServiceLaunchTime(value: Instant) extends AnyVal
final private[guard] case class ServiceBrief(value: Option[Json]) extends AnyVal
final private[guard] case class Policy private (value: String) extends AnyVal
private[guard] object Policy {
  def apply[F[_]](rp: RetryPolicy[F]): Policy = Policy(rp.show)
}

final private[guard] case class ActionName(value: String) extends AnyVal
final private[guard] case class Measurement(value: String) extends AnyVal