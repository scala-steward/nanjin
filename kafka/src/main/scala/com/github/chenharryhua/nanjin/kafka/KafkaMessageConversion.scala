package com.github.chenharryhua.nanjin.kafka

import cats.Bitraverse
import cats.implicits._
import monocle.Iso

import scala.util.{Failure, Success, Try}

trait KafkaMessageConversion[K, V] {
  val keyIso: Iso[Array[Byte], K]
  val valueIso: Iso[Array[Byte], V]

  final private def option[A](a: A): Try[A] =
    Option(a).fold[Try[A]](Failure(new Exception("null object")))(Success(_))

  implicit class KafkaDecode[F[_, _]: Bitraverse](data: F[Array[Byte], Array[Byte]]) {

    def native: F[Try[K], Try[V]] =
      data.bimap(
        k => option(k).flatMap(x => Try(keyIso.get(x))),
        v => option(v).flatMap(x => Try(valueIso.get(x))))

    def decode: Try[F[K, V]] =
      data.bitraverse(
        k => option(k).flatMap(x => Try(keyIso.get(x))),
        v => option(v).flatMap(x => Try(valueIso.get(x))))

    def value: Try[F[Array[Byte], V]] =
      data.bitraverse(Success(_), v => option(v).flatMap(x => Try(valueIso.get(x))))

    def key: Try[F[K, Array[Byte]]] =
      data.bitraverse(k => option(k).flatMap(x => Try(keyIso.get(x))), Success(_))
  }

  implicit class KafkaEncode[F[_, _]: Bitraverse](msg: F[K, V]) {

    def encode: F[Array[Byte], Array[Byte]] =
      msg.bimap(k => keyIso.reverseGet(k), v => valueIso.reverseGet(v))
  }
}
