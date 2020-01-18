package mtest.kafka.codec

import akka.kafka.ProducerMessage.{MultiMessage => AkkaMultiMessage}
import cats.effect.IO
import cats.implicits._
import com.github.chenharryhua.nanjin.kafka.codec._
import com.github.chenharryhua.nanjin.kafka.codec.eq._
import fs2.kafka.{
  CommittableProducerRecords   => Fs2CommittableProducerRecords,
  ProducerRecords              => Fs2ProducerRecords,
  TransactionalProducerRecords => Fs2TransactionalProducerRecords
}
import monocle.law.discipline.TraversalTests
import org.scalatest.funsuite.AnyFunSuite
import org.typelevel.discipline.scalatest.FunSuiteDiscipline
import org.scalatest.prop.Configuration

class TraversalTest extends AnyFunSuite with FunSuiteDiscipline with Configuration{
  checkAll(
    "akka.producer.MultiMessage",
    TraversalTests(
      BitraverseMessages[AkkaMultiMessage[*, *, String]].traversal[Int, Int, Int, Int]))

  checkAll(
    "fs2.producer.ProducerRecords",
    TraversalTests(
      BitraverseMessages[Fs2ProducerRecords[*, *, String]].traversal[Int, Int, Int, Int]))

  checkAll(
    "fs2.producer.CommittableProducerRecords",
    TraversalTests(
      BitraverseMessages[Fs2CommittableProducerRecords[IO, *, *]].traversal[Int, Int, Int, Int])
  )

  checkAll(
    "fs2.producer.TransactionalProducerRecords",
    TraversalTests(
      BitraverseMessages[Fs2TransactionalProducerRecords[IO, *, *, String]]
        .traversal[Int, Int, Int, Int])
  )
}
