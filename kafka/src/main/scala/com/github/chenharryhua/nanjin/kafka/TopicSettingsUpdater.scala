package com.github.chenharryhua.nanjin.kafka

import cats.data.Reader
import com.github.chenharryhua.nanjin.common.UpdateConfig

object akkaUpdater {

  import akka.kafka.{CommitterSettings, ConsumerSettings, ProducerSettings}

  final class Consumer(
    val settings: Reader[ConsumerSettings[Array[Byte], Array[Byte]], ConsumerSettings[Array[Byte], Array[Byte]]])
      extends UpdateConfig[ConsumerSettings[Array[Byte], Array[Byte]], Consumer] {

    override def updateConfig(
      f: ConsumerSettings[Array[Byte], Array[Byte]] => ConsumerSettings[Array[Byte], Array[Byte]]): Consumer =
      new Consumer(settings.andThen(f))
  }

  final class Producer[K, V](val settings: Reader[ProducerSettings[K, V], ProducerSettings[K, V]])
      extends UpdateConfig[ProducerSettings[K, V], Producer[K, V]] {

    override def updateConfig(f: ProducerSettings[K, V] => ProducerSettings[K, V]): Producer[K, V] =
      new Producer[K, V](settings.andThen(f))
  }

  final class Committer(val settings: Reader[CommitterSettings, CommitterSettings])
      extends UpdateConfig[CommitterSettings, Committer] {

    override def updateConfig(f: CommitterSettings => CommitterSettings): Committer =
      new Committer(settings.andThen(f))
  }

  val noUpdateConsumer: Consumer             = new Consumer(Reader(identity))
  def noUpdateProducer[K, V]: Producer[K, V] = new Producer[K, V](Reader(identity))
  val noUpdateCommitter: Committer           = new Committer(Reader(identity))

}

object fs2Updater {
  import fs2.kafka.{ConsumerSettings, ProducerSettings}

  final class Consumer[F[_]](
    val settings: Reader[ConsumerSettings[F, Array[Byte], Array[Byte]], ConsumerSettings[F, Array[Byte], Array[Byte]]])
      extends UpdateConfig[ConsumerSettings[F, Array[Byte], Array[Byte]], Consumer[F]] {

    override def updateConfig(
      f: ConsumerSettings[F, Array[Byte], Array[Byte]] => ConsumerSettings[F, Array[Byte], Array[Byte]]): Consumer[F] =
      new Consumer(settings.andThen(f))
  }

  final class Producer[F[_], K, V](val settings: Reader[ProducerSettings[F, K, V], ProducerSettings[F, K, V]])
      extends UpdateConfig[ProducerSettings[F, K, V], Producer[F, K, V]] {

    override def updateConfig(f: ProducerSettings[F, K, V] => ProducerSettings[F, K, V]): Producer[F, K, V] =
      new Producer[F, K, V](settings.andThen(f))
  }

  def noUpdateConsumer[F[_]]: Consumer[F]             = new Consumer[F](Reader(identity))
  def noUpdateProducer[F[_], K, V]: Producer[F, K, V] = new Producer[F, K, V](Reader(identity))

}
