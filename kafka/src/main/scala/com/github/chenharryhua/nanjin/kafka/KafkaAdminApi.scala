package com.github.chenharryhua.nanjin.kafka

import cats.effect.{Concurrent, ContextShift, Resource}
import cats.implicits._
import cats.tagless.autoFunctorK
import fs2.kafka.{adminClientResource, AdminClientSettings, KafkaAdminClient}
import org.apache.kafka.clients.admin.TopicDescription
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition

final case class KafkaConsumerGroupInfo(
  groupId: KafkaConsumerGroupId,
  gap: GenericTopicPartition[KafkaOffsetRange])

object KafkaConsumerGroupInfo {

  def apply(
    groupId: String,
    end: GenericTopicPartition[Option[KafkaOffset]],
    offsetMeta: Map[TopicPartition, OffsetAndMetadata]): KafkaConsumerGroupInfo = {
    val gaps = offsetMeta.map {
      case (tp, om) =>
        end.get(tp).flatten.map(e => tp -> KafkaOffsetRange(KafkaOffset(om.offset()), e))
    }.toList.flatten.toMap
    new KafkaConsumerGroupInfo(KafkaConsumerGroupId(groupId), GenericTopicPartition(gaps))
  }
}

// delegate to https://ovotech.github.io/fs2-kafka/
@autoFunctorK
sealed abstract class KafkaTopicAdminApi[F[_]] {
  def IdefinitelyWantDeleteTheTopic: F[Unit]
  def describe: F[Map[String, TopicDescription]]
  def groups: F[List[KafkaConsumerGroupInfo]]
}

object KafkaTopicAdminApi {

  def apply[F[_]: Concurrent: ContextShift, K, V](
    topic: KafkaTopic[F, K, V],
    adminSettings: AdminClientSettings[F]
  ): KafkaTopicAdminApi[F] = new DelegateToFs2(topic, adminSettings)

  final private class DelegateToFs2[F[_]: Concurrent: ContextShift, K, V](
    topic: KafkaTopic[F, K, V],
    adminSettings: AdminClientSettings[F])
      extends KafkaTopicAdminApi[F] {

    private val admin: Resource[F, KafkaAdminClient[F]] =
      adminClientResource[F](adminSettings)

    override def IdefinitelyWantDeleteTheTopic: F[Unit] =
      admin.use(_.deleteTopic(topic.topicName))

    override def describe: F[Map[String, TopicDescription]] =
      admin.use(_.describeTopics(List(topic.topicName)))

    override def groups: F[List[KafkaConsumerGroupInfo]] =
      admin.use { client =>
        for {
          end <- topic.consumer.endOffsets
          gids <- client.listConsumerGroups.groupIds
          all <- gids.traverse(
            gid =>
              client
                .listConsumerGroupOffsets(gid)
                .partitionsToOffsetAndMetadata
                .map(m => KafkaConsumerGroupInfo(gid, end, m)))
        } yield all.filter(_.gap.nonEmpty)
      }
  }
}
