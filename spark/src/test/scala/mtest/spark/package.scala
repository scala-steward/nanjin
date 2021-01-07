package mtest

import akka.actor.ActorSystem
import akka.stream.Materializer
import cats.effect.{Blocker, ContextShift, IO, Timer}
import com.github.chenharryhua.nanjin.datetime.sydneyTime
import com.github.chenharryhua.nanjin.kafka.{IoKafkaContext, KafkaSettings}
import com.github.chenharryhua.nanjin.spark.SparkSettings
import org.apache.spark.sql.SparkSession
import com.github.chenharryhua.nanjin.spark._

import scala.concurrent.ExecutionContext.Implicits.global

package object spark {
  val akkaSystem: ActorSystem    = ActorSystem("nj-spark")
  implicit val mat: Materializer = Materializer(akkaSystem)

  implicit val contextShift: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO]               = IO.timer(global)

  val blocker: Blocker = Blocker.liftExecutionContext(global)

  val ctx: IoKafkaContext =
    KafkaSettings.local.withApplicationId("kafka.stream.test.app").withGroupId("spark.kafka.stream.test").ioContext

  val sparkSession: SparkSession = SparkSettings.default
    .withAppName("nj.spark.test")
    .withUI
    .withoutUI
    .withConfigUpdate(_.set("spark.sql.session.timeZone", sydneyTime.toString))
    .session

  val sparKafka: SparKafkaContext[IO] = sparkSession.alongWith(ctx)

}
