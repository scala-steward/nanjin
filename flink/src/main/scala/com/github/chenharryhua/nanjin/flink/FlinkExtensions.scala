package com.github.chenharryhua.nanjin.flink

import com.github.chenharryhua.nanjin.kafka.{KafkaTopicDescription, NJConsumerRecord}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer, KafkaDeserializationSchema}
import org.apache.kafka.clients.consumer.ConsumerRecord

private[flink] trait FlinkExtensions extends Serializable {

  implicit final class FlinKafkaExtension[K, V](description: KafkaTopicDescription[K, V])
      extends Serializable {

    def dataStream(env: StreamExecutionEnvironment)(
      implicit
      keyInfo: TypeInformation[K],
      valInfo: TypeInformation[V]): DataStream[NJConsumerRecord[K, V]] =
      env.addSource[NJConsumerRecord[K, V]](
        new FlinkKafkaConsumer[NJConsumerRecord[K, V]](
          description.topicDef.topicName.value,
          new KafkaDeserializationSchema[NJConsumerRecord[K, V]] {
            override def isEndOfStream(nextElement: NJConsumerRecord[K, V]): Boolean = false

            override def getProducedType: TypeInformation[NJConsumerRecord[K, V]] =
              implicitly[TypeInformation[NJConsumerRecord[K, V]]]

            override def deserialize(
              record: ConsumerRecord[Array[Byte], Array[Byte]]): NJConsumerRecord[K, V] =
              description.decoder(record).record
          },
          description.settings.consumerSettings.consumerProperties))
  }
}
