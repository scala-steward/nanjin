package com.github.chenharryhua.nanjin.spark.dstream

import cats.data.Reader
import cats.effect.Sync
import fs2.Stream
import org.apache.spark.SparkContext
import org.apache.spark.streaming.{Duration, Seconds, StreamingContext}

import scala.concurrent.duration.FiniteDuration

final class EndMark private {}

private[dstream] object EndMark {
  val mark: EndMark = new EndMark
}

final class DStreamRunner[F[_]] private (
  sparkContext: SparkContext,
  checkpoint: String,
  batchDuration: Duration,
  streamings: List[Reader[StreamingContext, EndMark]])
    extends Serializable {

  def signup[A](rd: Reader[StreamingContext, A])(f: A => EndMark): DStreamRunner[F] =
    new DStreamRunner[F](sparkContext, checkpoint, batchDuration, rd.map(f) :: streamings)

  private def createContext(): StreamingContext = {
    val ssc = new StreamingContext(sparkContext, batchDuration)
    streamings.foreach(_(ssc))
    ssc.checkpoint(checkpoint)
    ssc
  }

  def run(implicit F: Sync[F]): Stream[F, Unit] =
    Stream
      .bracket(F.delay {
        val sc: StreamingContext = StreamingContext.getOrCreate(checkpoint, createContext)
        sc.start()
        sc
      })(sc => F.delay(sc.stop(stopSparkContext = false, stopGracefully = true)))
      .evalMap(sc => F.delay(sc.awaitTermination()))
}

object DStreamRunner {

  def apply[F[_]](sparkContext: SparkContext, checkpoint: String, batchDuration: FiniteDuration): DStreamRunner[F] =
    new DStreamRunner[F](sparkContext, checkpoint, Seconds(batchDuration.toSeconds), Nil)
}
