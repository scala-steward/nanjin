package mtest.spark.streaming

import org.scalatest.Sequential

class SparkStreamTest extends Sequential(new SparkDStreamTest, new SparkKafkaStreamTest)
