package mtest.spark.sstream

import org.scalatest.Sequential

class SparkStreamTest extends Sequential(new KafkaStreamTest, new StreamJoinTest)
