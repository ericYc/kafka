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
import java.util.Properties

import kafka.server.KafkaConfig
import org.junit.{After, Before, Test}
import kafka.utils.JaasTestUtils
import org.apache.kafka.common.security.auth.SecurityProtocol

import scala.collection.JavaConverters._

class SaslMultiMechanismConsumerTest extends BaseConsumerTest with SaslSetup {
  private val kafkaClientSaslMechanism = "OAUTHBEARER"
  private val kafkaServerSaslMechanisms = List("GSSAPI", "OAUTHBEARER")
  this.serverConfig.setProperty(KafkaConfig.ZkEnableSecureAclsProp, "true")
  override protected def securityProtocol = SecurityProtocol.SASL_SSL
  override protected lazy val trustStoreFile = Some(File.createTempFile("truststore", ".jks"))
  override protected val serverSaslProperties = Some(kafkaServerSaslProperties(kafkaServerSaslMechanisms, "GSSAPI"))
  override protected val clientSaslProperties = Some(kafkaClientSaslProperties(kafkaClientSaslMechanism))


  override def modifyConfigs(props: Seq[Properties]): Unit = {
    props.foreach { cfg =>
      cfg.setProperty("listener.name.sasl_ssl.oauthbearer.sasl.jaas.config",
        "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;")
      cfg.setProperty("sasl.kerberos.service.name", "kafka")
    }
    super.modifyConfigs(props)
  }

  @Before
  override def setUp(): Unit = {
    startSasl(jaasSections(kafkaServerSaslMechanisms, Some(kafkaClientSaslMechanism), Both,
      JaasTestUtils.KafkaServerContextName))
    super.setUp()
  }

  @After
  override def tearDown(): Unit = {
    super.tearDown()
    closeSasl()
  }

  @Test
  def testMultipleBrokerMechanisms(): Unit = {
    val plainSaslProducer = createProducer()
    val plainSaslConsumer = createConsumer()

    val gssapiSaslProperties = kafkaClientSaslProperties("GSSAPI", dynamicJaasConfig = true)
    val gssapiSaslProducer = createProducer(configOverrides = gssapiSaslProperties)
    val gssapiSaslConsumer = createConsumer(configOverrides = gssapiSaslProperties)
    val numRecords = 1000
    var startingOffset = 0

    // Test SASL/PLAIN producer and consumer
    sendRecords(plainSaslProducer, numRecords, tp)
    plainSaslConsumer.assign(List(tp).asJava)
    plainSaslConsumer.seek(tp, 0)
    consumeAndVerifyRecords(consumer = plainSaslConsumer, numRecords = numRecords, startingOffset = startingOffset)
    sendAndAwaitAsyncCommit(plainSaslConsumer)
    startingOffset += numRecords

    // Test SASL/GSSAPI producer and consumer
    sendRecords(gssapiSaslProducer, numRecords, tp)
    gssapiSaslConsumer.assign(List(tp).asJava)
    gssapiSaslConsumer.seek(tp, startingOffset)
    consumeAndVerifyRecords(consumer = gssapiSaslConsumer, numRecords = numRecords, startingOffset = startingOffset)
    sendAndAwaitAsyncCommit(gssapiSaslConsumer)
    startingOffset += numRecords

    // Test SASL/PLAIN producer and SASL/GSSAPI consumer
    sendRecords(plainSaslProducer, numRecords, tp)
    gssapiSaslConsumer.assign(List(tp).asJava)
    gssapiSaslConsumer.seek(tp, startingOffset)
    consumeAndVerifyRecords(consumer = gssapiSaslConsumer, numRecords = numRecords, startingOffset = startingOffset)
    startingOffset += numRecords

    // Test SASL/GSSAPI producer and SASL/PLAIN consumer
    sendRecords(gssapiSaslProducer, numRecords, tp)
    plainSaslConsumer.assign(List(tp).asJava)
    plainSaslConsumer.seek(tp, startingOffset)
    consumeAndVerifyRecords(consumer = plainSaslConsumer, numRecords = numRecords, startingOffset = startingOffset)
  }

}
