package mtest.spark

import akka.actor.ActorSystem
import cats.effect.{Blocker, ContextShift, IO, Timer}
import com.github.chenharryhua.nanjin.common.NJLogLevel
import com.github.chenharryhua.nanjin.database._
import com.github.chenharryhua.nanjin.datetime.{beijingTime, NJDateTimeRange}
import com.github.chenharryhua.nanjin.kafka.{IoKafkaContext, KafkaSettings}
import com.github.chenharryhua.nanjin.spark.SparkSettings
import org.apache.spark.sql.SparkSession

import scala.concurrent.ExecutionContext.Implicits.global

package object kafka {

  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO]     = IO.timer(global)
  val blocker: Blocker              = Blocker.liftExecutionContext(global)

  implicit val akkaSystem: ActorSystem = ActorSystem("nj-spark")

  val ctx: IoKafkaContext = KafkaSettings.local.withGroupId("spark.kafka.unit.test").ioContext

  implicit val sparkSession: SparkSession =
    SparkSettings.default
      .withConfigUpdate(_.setMaster("local[*]").setAppName("test-spark"))
      .withLogLevel(NJLogLevel.ERROR)
      .withKms("alias/na")
      .session

  val range: NJDateTimeRange = NJDateTimeRange(beijingTime)
}
