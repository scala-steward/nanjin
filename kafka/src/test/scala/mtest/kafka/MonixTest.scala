package mtest.kafka

import cats.effect.ConcurrentEffect
import com.github.chenharryhua.nanjin.kafka.{KafkaSettings, MonixKafkaContext, TopicDef,TopicName}
import monix.eval.Task
import monix.eval.instances.CatsConcurrentEffectForTask
import monix.execution.Scheduler
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._

class MonixTest extends AnyFunSuite {
  implicit val scheduler: Scheduler = Scheduler.global
  val options: Task.Options         = Task.defaultOptions.withSchedulerFeatures(scheduler)

  implicit lazy val catsEffect: ConcurrentEffect[Task] =
    new CatsConcurrentEffectForTask()(scheduler, options)
  val ctx: MonixKafkaContext = KafkaSettings.local.monixContext

  test("monix should just work") {
    val topic = TopicDef[String, trip_record](TopicName("nyc_yellow_taxi_trip_data")).in(ctx)
    val task =
      topic.fs2Channel.stream
        .map(m => topic.njDecoder.decode(m).run._2)
        .take(3)
        .map(println)
        .compile
        .drain
    task.runSyncUnsafe(10.seconds)
  }
}
