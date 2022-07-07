package com.github.chenharryhua.nanjin.spark

import com.github.chenharryhua.nanjin.common.kafka.TopicName
import com.github.chenharryhua.nanjin.kafka.{KafkaContext, KafkaTopic, TopicDef}
import com.github.chenharryhua.nanjin.messages.kafka.codec.{KJson, SerdeOf}
import com.github.chenharryhua.nanjin.spark.kafka.SparKafkaTopic
import io.circe.Json
import org.apache.spark.sql.SparkSession
import org.typelevel.cats.time.instances.zoneid

final class SparKafkaContext[F[_]](val sparkSession: SparkSession, val kafkaContext: KafkaContext[F])
    extends Serializable with zoneid {

  def topic[K, V](topicDef: TopicDef[K, V]): SparKafkaTopic[F, K, V] =
    new SparKafkaTopic[F, K, V](sparkSession, topicDef.in[F](kafkaContext))

  def topic[K, V](kt: KafkaTopic[F, K, V]): SparKafkaTopic[F, K, V] =
    topic[K, V](kt.topicDef)

  def topic[K: SerdeOf, V: SerdeOf](topicName: TopicName): SparKafkaTopic[F, K, V] =
    topic[K, V](TopicDef[K, V](topicName))

  def byteTopic(topicName: TopicName): SparKafkaTopic[F, Array[Byte], Array[Byte]] =
    topic[Array[Byte], Array[Byte]](topicName)

  def stringTopic(topicName: TopicName): SparKafkaTopic[F, String, String] =
    topic[String, String](topicName)

  def jsonTopic(topicName: TopicName): SparKafkaTopic[F, KJson[Json], KJson[Json]] =
    topic[KJson[Json], KJson[Json]](topicName)
}