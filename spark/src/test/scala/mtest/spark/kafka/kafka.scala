package mtest.spark

import cats.effect.IO
import com.github.chenharryhua.nanjin.datetime.zones.beijingTime
import com.github.chenharryhua.nanjin.datetime.NJDateTimeRange
import com.github.chenharryhua.nanjin.kafka.{KafkaContext, KafkaSettings}
import com.github.chenharryhua.nanjin.spark.*
import com.github.chenharryhua.nanjin.terminals.NJHadoop
package object kafka {

  val range: NJDateTimeRange = NJDateTimeRange(beijingTime)

  val ctx: KafkaContext[IO]           = KafkaSettings.local.ioContext
  val sparKafka: SparKafkaContext[IO] = sparkSession.alongWith(ctx)
  val hadoop: NJHadoop[IO] = sparkSession.hadoop[IO]

}
