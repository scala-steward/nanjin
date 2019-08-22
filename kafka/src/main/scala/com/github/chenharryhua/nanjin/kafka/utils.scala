package com.github.chenharryhua.nanjin.kafka

import java.time.{Instant, LocalDateTime, ZoneId, ZonedDateTime}
import java.util.Properties

import cats.Eval

import scala.reflect.ClassTag
import scala.util.{Failure, Random, Success, Try}

object utils {

  def toProperties(props: Map[String, String]): Properties =
    (new Properties() /: props) { case (a, (k, v)) => a.put(k, v); a }

  val random4d: Eval[Int] = Eval.always(1000 + Random.nextInt(9000))

  def kafkaTimestamp(t: Long, tz: ZoneId = ZoneId.systemDefault()): (Instant, ZonedDateTime) = {
    val utc = Instant.ofEpochMilli(t)
    (utc, utc.atZone(tz))
  }

  def kafkaTimestamp2LocalDateTime(ts: Long, tz: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), tz)

  def localDateTime2KafkaTimestamp(dt: LocalDateTime, tz: ZoneId = ZoneId.systemDefault()): Long =
    dt.atZone(tz).toInstant.toEpochMilli

  def nullable[A](a: A): Try[A] =
    Option(a).fold[Try[A]](Failure(new Exception("null object")))(Success(_))
}

/**
  * Holds a variable shared among all workers. Useful to use non-serializable objects in Spark closures.
  *
  * @author Nicola Ferraro
  */
final class SharedVariable[T: ClassTag](constructor: => T) extends AnyRef with Serializable {
  @transient private lazy val instance: T = constructor
  def get: T                              = instance
}

object SharedVariable {
  def apply[T: ClassTag](constructor: => T): SharedVariable[T] = new SharedVariable[T](constructor)
}
