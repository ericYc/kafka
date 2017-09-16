/**
  * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
  * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
  * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
  * License. You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
  * specific language governing permissions and limitations under the License.
  */
package kafka.api

import java.io.File
import java.util.{Locale, Properties}

import kafka.log.LogConfig
import kafka.server.{KafkaConfig, KafkaServer}
import kafka.utils.{JaasTestUtils, TestUtils}

import com.yammer.metrics.Metrics
import com.yammer.metrics.core.{Gauge, Histogram, Meter}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.{Metric, MetricName, TopicPartition}
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.errors.InvalidTopicException
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.protocol.SecurityProtocol
import org.junit.{After, Before, Test}
import org.junit.Assert._

import scala.collection.JavaConverters._
import org.apache.kafka.clients.consumer.KafkaConsumer
import java.util.concurrent.TimeUnit

class MetricsTest extends IntegrationTestHarness with SaslSetup {

  override val producerCount = 1
  override val consumerCount = 1
  override val serverCount = 1

  override protected def listenerName = new ListenerName("CLIENT")
  private val kafkaClientSaslMechanism = "PLAIN"
  private val kafkaServerSaslMechanisms = List(kafkaClientSaslMechanism)
  private val kafkaServerJaasEntryName =
    s"${listenerName.value.toLowerCase(Locale.ROOT)}.${JaasTestUtils.KafkaServerContextName}"
  this.serverConfig.setProperty(KafkaConfig.ZkEnableSecureAclsProp, "false")
  this.serverConfig.setProperty(KafkaConfig.AutoCreateTopicsEnableDoc, "false")
  override protected def securityProtocol = SecurityProtocol.SASL_PLAINTEXT
  override protected val serverSaslProperties = Some(kafkaServerSaslProperties(kafkaServerSaslMechanisms, kafkaClientSaslMechanism))
  override protected val clientSaslProperties = Some(kafkaClientSaslProperties(kafkaClientSaslMechanism))

  @Before
  override def setUp(): Unit = {
    startSasl(jaasSections(kafkaServerSaslMechanisms, Some(kafkaClientSaslMechanism), KafkaSasl, kafkaServerJaasEntryName))
    super.setUp()
  }

  @After
  override def tearDown(): Unit = {
    super.tearDown()
    closeSasl()
  }

  /**
   * Verifies some of the metrics of producer, consumer as well as server.
   */
  @Test
  def testMetrics(): Unit = {
    val topic = "topicWithOldMessageFormat"
    val props = new Properties
    props.setProperty(LogConfig.MessageFormatVersionProp, "0.9.0")
    TestUtils.createTopic(this.zkUtils, topic, numPartitions = 1, replicationFactor = 1, this.servers, props)
    val tp = new TopicPartition(topic, 0)

    // Produce and consume some records
    val numRecords = 10
    val producer = producers.head
    sendRecords(producer, numRecords, tp)

    val consumer = this.consumers.head
    consumer.assign(List(tp).asJava)
    consumer.seek(tp, 0)
    consumeRecords(consumer, numRecords)

    verifyKafkaRateMetricsHaveCumulativeCount()
    verifyClientVersionMetrics(consumer.metrics, "Consumer")
    verifyClientVersionMetrics(this.producers.head.metrics, "Producer")

    val server = servers.head
    generateAuthenticationFailure(tp)
    verifyBrokerAuthenticationMetrics(server)
    verifyBrokerMessageConversionMetrics(server)
    verifyBrokerZKMetrics(server)
    generateError()
    verifyBrokerErrorMetrics(servers.head)
  }

  private def sendRecords(producer: KafkaProducer[Array[Byte], Array[Byte]], numRecords: Int,  tp: TopicPartition) = {
    val bytes = new Array[Byte](1000)
    (0 until numRecords).map { i =>
      producer.send(new ProducerRecord(tp.topic(), tp.partition(), i.toLong, s"key $i".getBytes, bytes))
    }
    producer.flush()
  }

  protected def consumeRecords(consumer: KafkaConsumer[Array[Byte], Array[Byte]], numRecords: Int): Unit = {
    val maxIters = numRecords * 300
    var iters = 0
    var received = 0
    while (received < numRecords && iters < maxIters) {
      received += consumer.poll(50).count
      iters += 1
    }
    assertEquals(numRecords, received)
  }

  // Create a producer that fails authentication to verify authentication failure metrics
  private def generateAuthenticationFailure(tp: TopicPartition): Unit = {
    val producerProps = new Properties()
    val saslProps = new Properties()
     // Temporary limit to reduce blocking before KIP-152 client-side changes are merged
    saslProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "1000")
    saslProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "1000")
    saslProps.put(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-256")
    // Use acks=0 to verify error metric when connection is closed without a response
    saslProps.put(ProducerConfig.ACKS_CONFIG, "0")
    val producer = TestUtils.createNewProducer(brokerList, securityProtocol = securityProtocol, trustStoreFile = trustStoreFile,
        saslProperties = Some(saslProps), retries = 0, lingerMs = Long.MaxValue, props = Some(producerProps))

    try {
      producer.send(new ProducerRecord(tp.topic, tp.partition, "key".getBytes, "value".getBytes)).get
    } catch {
      case _: Exception => // expected exception
    } finally {
      producer.close()
    }
  }

  // Generate an error to verify error metrics
  private def generateError(): Unit = {
    try {
      consumers.head.partitionsFor("12{}!")
    } catch {
      case e: InvalidTopicException => // expected
    }
  }

  private def verifyKafkaRateMetricsHaveCumulativeCount(): Unit =  {

    def exists(name: String, rateMetricName: MetricName, allMetricNames: Set[MetricName]): Boolean = {
      allMetricNames.contains(new MetricName(name, rateMetricName.group, "", rateMetricName.tags))
    }

    def verify(rateMetricName: MetricName, allMetricNames: Set[MetricName]): Unit = {
      val name = rateMetricName.name
      val totalExists = exists(name.replace("-rate", "-total"), rateMetricName, allMetricNames)
      val totalTimeExists = exists(name.replace("-rate", "-time"), rateMetricName, allMetricNames)
      assertTrue(s"No cumulative count/time metric for rate metric $rateMetricName",
          totalExists || totalTimeExists)
    }

    val consumer = this.consumers.head
    val consumerMetricNames = consumer.metrics.keySet.asScala.toSet
    consumerMetricNames.filter(_.name.endsWith("-rate"))
        .foreach(verify(_, consumerMetricNames))

    val producer = this.producers.head
    val producerMetricNames = producer.metrics.keySet.asScala.toSet
    val producerExclusions = Set("compression-rate") // compression-rate is an Average metric, not Rate
    producerMetricNames.filter(_.name.endsWith("-rate"))
        .filterNot(metricName => producerExclusions.contains(metricName.name))
        .foreach(verify(_, producerMetricNames))

    // Check a couple of metrics of consumer and producer to ensure that values are set
    verifyKafkaMetricRecorded("records-consumed-rate", consumer.metrics, "Consumer")
    verifyKafkaMetricRecorded("records-consumed-total", consumer.metrics, "Consumer")
    verifyKafkaMetricRecorded("record-send-rate", producer.metrics, "Producer")
    verifyKafkaMetricRecorded("record-send-total", producer.metrics, "Producer")
  }

  private def verifyClientVersionMetrics(metrics: java.util.Map[MetricName, _ <: Metric], entity: String): Unit = {
    Seq("commit-id", "version").foreach { name =>
      verifyKafkaMetric(name, metrics, entity) { metric =>
        val value = metric.metricValue
        assertNotNull(s"$entity metric not recorded $name", value)
        assertNotNull(s"$entity metric $name should be a non-empty String", value.isInstanceOf[String] && !value.asInstanceOf[String].isEmpty)
        assertTrue("Client-id not specified", metric.metricName.tags.containsKey("client-id"))
      }
    }
  }

  private def verifyBrokerAuthenticationMetrics(server: KafkaServer): Unit = {
    val metrics = server.metrics.metrics
    verifyKafkaMetricRecorded("successful-authentication-rate", metrics, "Broker", Some("socket-server-metrics"))
    verifyKafkaMetricRecorded("successful-authentication-total", metrics, "Broker", Some("socket-server-metrics"))
    verifyKafkaMetricRecorded("failed-authentication-rate", metrics, "Broker", Some("socket-server-metrics"))
    verifyKafkaMetricRecorded("failed-authentication-total", metrics, "Broker", Some("socket-server-metrics"))
  }

  private def verifyBrokerMessageConversionMetrics(server: KafkaServer): Unit = {
    val requestSize = verifyYammerMetricRecorded(s"RequestSize,request=Produce")
    val tempSize = verifyYammerMetricRecorded(s"TemporaryMemorySize,request=Produce")
    assertTrue(s"Unexpected temporary memory size requestSize $requestSize tempSize $tempSize",
        tempSize >= requestSize && tempSize <= requestSize + 1000)

    verifyYammerMetricRecorded(s"ProduceMessageConversionsPerSec")
    verifyYammerMetricRecorded(s"MessageConversionsTimeMs,request=Produce", value => value > 0.0)

    verifyYammerMetricRecorded(s"RequestSize,request=Fetch")
    verifyYammerMetricRecorded(s"TemporaryMemorySize,request=Fetch", value => value == 0.0)

    verifyYammerMetricRecorded(s"RequestSize,request=Metadata") // request size recorded for all request types, check one
  }

  private def verifyBrokerZKMetrics(server: KafkaServer): Unit = {
    verifyYammerMetricRecorded("ZooKeeperLatency")
    assertEquals("CONNECTED", yammerMetricValue("SessionState"))
  }

  private def verifyBrokerErrorMetrics(server: KafkaServer): Unit = {
    verifyYammerMetricRecorded("name=ErrorsPerSec,request=Metadata,error=INVALID_TOPIC_EXCEPTION")
    verifyYammerMetricRecorded("ZooKeeperLatency")
    assertEquals("CONNECTED", yammerMetricValue("SessionState"))

    def errorMetricCount = Metrics.defaultRegistry.allMetrics.keySet.asScala.filter(_.getName == "ErrorsPerSec").size

    // Check that error metrics are registered dynamically
    assertEquals(1, errorMetricCount)

    // Verify that error metric is updated with producer acks=0 when no response is sent
    sendRecords(producers.head, 1, new TopicPartition("non-existent", 0))
    verifyYammerMetricRecorded("name=ErrorsPerSec,request=Metadata,error=LEADER_NOT_AVAILABLE")
    assertEquals(2, errorMetricCount)
  }

  private def verifyKafkaMetric(name: String, metrics: java.util.Map[MetricName, _ <: Metric], entity: String,
      group: Option[String] = None)(verify: Metric => Unit) : Unit = {
    val (_, metric) = metrics.asScala.find { case (metricName, _) => metricName.name == name && group.forall( _ == metricName.group)}
      .getOrElse(fail(s"$entity metric not defined $name"))
    verify(metric)
  }

  private def verifyKafkaMetricRecorded(name: String, metrics: java.util.Map[MetricName, _ <: Metric], entity: String,
      group: Option[String] = None): Unit = {
    verifyKafkaMetric(name, metrics, entity, group) { metric =>
      assertTrue(s"$entity metric not recorded correctly for ${metric.metricName} value ${metric.metricValue}", metric.value > 0.0)
    }
  }

  private def yammerMetricValue(name: String): Any = {
    val allMetrics = Metrics.defaultRegistry.allMetrics.asScala
    val (metricName, metric) = allMetrics.find { case (n, _) => n.getMBeanName.endsWith(name) }
      .getOrElse(fail(s"Unable to find broker metric $name: allMetrics: ${allMetrics.keySet.map(n => n.getMBeanName)}"))
    metric match {
      case m: Meter => m.count.toDouble
      case m: Histogram => m.max
      case m: Gauge[_] => m.value
      case m => fail(s"Unexpected broker metric of class ${m.getClass}")
    }
  }

  private def verifyYammerMetricRecorded(name: String, verify: Double => Boolean = d => d > 0): Double = {
    val metricValue = yammerMetricValue(name).asInstanceOf[Double]
    assertTrue(s"Broker metric not recorded correctly for $name value $metricValue", verify(metricValue))
    metricValue
  }
}
