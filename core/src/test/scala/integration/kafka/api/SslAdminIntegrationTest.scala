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
import java.util
import java.util.Collections
import java.util.concurrent._
import java.util.function.BiConsumer

import com.yammer.metrics.Metrics
import com.yammer.metrics.core.Gauge
import kafka.security.authorizer.AclAuthorizer
import kafka.security.authorizer.AuthorizerUtils.{WildcardHost, WildcardPrincipal}
import kafka.security.auth.{Operation, PermissionType}
import kafka.server.KafkaConfig
import kafka.utils.{CoreUtils, TestUtils}
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, CreateAclsResult}
import org.apache.kafka.common.acl._
import org.apache.kafka.common.acl.AclOperation._
import org.apache.kafka.common.acl.AclPermissionType._
import org.apache.kafka.common.protocol.ApiKeys
import org.apache.kafka.common.resource.{PatternType, Resource, ResourcePattern, ResourceType}
import org.apache.kafka.common.resource.PatternType._
import org.apache.kafka.common.resource.ResourceType._
import org.apache.kafka.common.security.auth.{KafkaPrincipal, SecurityProtocol}
import org.apache.kafka.server.authorizer._
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertTrue}
import org.junit.{Assert, Test}

import scala.collection.JavaConverters._
import scala.collection.mutable

object SslAdminIntegrationTest {
  @volatile var semaphore: Option[Semaphore] = None
  @volatile var executor: Option[ExecutorService] = None
  @volatile var lastUpdateRequestContext: Option[AuthorizableRequestContext] = None
  class TestableAclAuthorizer extends AclAuthorizer {
    override def createAcls(requestContext: AuthorizableRequestContext,
                            aclBindings: util.List[AclBinding]): util.List[_ <: CompletionStage[AclCreateResult]] = {
      lastUpdateRequestContext = Some(requestContext)
      execute[AclCreateResult](aclBindings.size, () => super.createAcls(requestContext, aclBindings))
    }

    override def deleteAcls(requestContext: AuthorizableRequestContext,
                            aclBindingFilters: util.List[AclBindingFilter]): util.List[_ <: CompletionStage[AclDeleteResult]] = {
      lastUpdateRequestContext = Some(requestContext)
      execute[AclDeleteResult](aclBindingFilters.size, () => super.deleteAcls(requestContext, aclBindingFilters))
    }

    private def execute[T](batchSize: Int, action: () => util.List[_ <: CompletionStage[T]]): util.List[CompletableFuture[T]] = {
      val futures = (0 until batchSize).map(_ => new CompletableFuture[T]).toList
      val runnable = new Runnable {
        override def run(): Unit = {
          semaphore.foreach(_.acquire())
          try {
            action.apply().asScala.zip(futures).foreach { case (baseFuture, resultFuture) =>
              baseFuture.whenComplete(new BiConsumer[T, Throwable]() {
                override def accept(result: T, exception: Throwable): Unit = {
                  if (exception != null)
                    resultFuture.completeExceptionally(exception)
                  else
                    resultFuture.complete(result)
                }
              })
            }
          } finally {
            semaphore.foreach(_.release())
          }
        }
      }
      executor match {
        case Some(executorService) => executorService.submit(runnable)
        case None => runnable.run()
      }
      futures.asJava
    }
  }
}

class SslAdminIntegrationTest extends SaslSslAdminIntegrationTest {
  val clusterResourcePattern = new ResourcePattern(ResourceType.CLUSTER, Resource.CLUSTER_NAME, PatternType.LITERAL)

  this.serverConfig.setProperty(KafkaConfig.ZkEnableSecureAclsProp, "true")
  this.serverConfig.setProperty(KafkaConfig.AuthorizerClassNameProp, classOf[SslAdminIntegrationTest.TestableAclAuthorizer].getName)

  override protected def securityProtocol = SecurityProtocol.SSL
  override protected lazy val trustStoreFile = Some(File.createTempFile("truststore", ".jks"))
  private val adminClients = mutable.Buffer.empty[AdminClient]

  override def configureSecurityBeforeServersStart(): Unit = {
    val authorizer = CoreUtils.createObject[Authorizer](classOf[AclAuthorizer].getName)
    try {
      authorizer.configure(this.configs.head.originals())
      val ace = new AccessControlEntry(WildcardPrincipal, WildcardHost, ALL, ALLOW)
      authorizer.createAcls(null, List(new AclBinding(new ResourcePattern(TOPIC, "*", LITERAL), ace)).asJava)
      authorizer.createAcls(null, List(new AclBinding(new ResourcePattern(GROUP, "*", LITERAL), ace)).asJava)

      authorizer.createAcls(null, List(clusterAcl(ALLOW, CREATE),
                             clusterAcl(ALLOW, DELETE),
                             clusterAcl(ALLOW, CLUSTER_ACTION),
                             clusterAcl(ALLOW, ALTER_CONFIGS),
                             clusterAcl(ALLOW, ALTER))
        .map(ace => new AclBinding(clusterResourcePattern, ace)).asJava)
    } finally {
      authorizer.close()
    }
  }

  override def setUpSasl(): Unit = {
    SslAdminIntegrationTest.semaphore = None
    SslAdminIntegrationTest.executor = None
    SslAdminIntegrationTest.lastUpdateRequestContext = None

    startSasl(jaasSections(List.empty, None, ZkSasl))
  }

  override def tearDown(): Unit = {
    // Ensure semaphore doesn't block shutdown even if test has failed
    val semaphore = SslAdminIntegrationTest.semaphore
    SslAdminIntegrationTest.semaphore = None
    semaphore.foreach(s => s.release(s.getQueueLength))

    adminClients.foreach(_.close())
    super.tearDown()
  }

  override def addClusterAcl(permissionType: PermissionType, operation: Operation): Unit = {
    val ace = clusterAcl(permissionType.toJava, operation.toJava)
    val aclBinding = new AclBinding(clusterResourcePattern, ace)
    val authorizer = servers.head.dataPlaneRequestProcessor.authorizer.get
    val prevAcls = authorizer.acls(new AclBindingFilter(clusterResourcePattern.toFilter, AccessControlEntryFilter.ANY))
      .asScala.map(_.entry).toSet
    authorizer.createAcls(null, Collections.singletonList(aclBinding))
    TestUtils.waitAndVerifyAcls(prevAcls ++ Set(ace), authorizer, clusterResourcePattern)
  }

  override def removeClusterAcl(permissionType: PermissionType, operation: Operation): Unit = {
    val ace = clusterAcl(permissionType.toJava, operation.toJava)
    val authorizer = servers.head.dataPlaneRequestProcessor.authorizer.get
    val clusterFilter = new AclBindingFilter(clusterResourcePattern.toFilter, AccessControlEntryFilter.ANY)
    val prevAcls = authorizer.acls(clusterFilter).asScala.map(_.entry).toSet
    val deleteFilter = new AclBindingFilter(clusterResourcePattern.toFilter, ace.toFilter)
    Assert.assertFalse(authorizer.deleteAcls(null, Collections.singletonList(deleteFilter))
      .get(0).toCompletableFuture.get.aclBindingDeleteResults().asScala.head.exception.isPresent)
    TestUtils.waitAndVerifyAcls(prevAcls -- Set(ace), authorizer, clusterResourcePattern)
  }

  private def clusterAcl(permissionType: AclPermissionType, operation: AclOperation): AccessControlEntry = {
    new AccessControlEntry(new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "*").toString,
      WildcardHost, operation, permissionType)
  }

  @Test
  def testAclUpdatesUsingSynchronousAuthorizer(): Unit = {
    verifyAclUpdates()
  }

  @Test
  def testAclUpdatesUsingAsynchronousAuthorizer(): Unit = {
    SslAdminIntegrationTest.executor = Some(Executors.newSingleThreadExecutor)
    verifyAclUpdates()
  }

  /**
   * Verify that ACL updates using synchronous authorizer are performed synchronously
   * on request threads without any performance overhead introduced by a purgatory.
   */
  @Test
  def testSynchronousAuthorizerAclUpdatesBlockRequestThreads(): Unit = {
    val testSemaphore = new Semaphore(0)
    SslAdminIntegrationTest.semaphore = Some(testSemaphore)
    waitForNoBlockedRequestThreads()

    // Queue requests until all threads are blocked. ACL create requests are sent to least loaded
    // node, so we may need more than `numRequestThreads` requests to block all threads.
    val aclFutures = mutable.Buffer[CreateAclsResult]()
    while (blockedRequestThreads.size < numRequestThreads) {
      aclFutures += createAdminClient.createAcls(List(acl2).asJava)
      assertTrue(s"Request threads not blocked numRequestThreads=$numRequestThreads blocked=$blockedRequestThreads",
        aclFutures.size < numRequestThreads * 10)
    }
    assertEquals(0, purgatoryMetric("NumDelayedOperations"))
    assertEquals(0, purgatoryMetric("PurgatorySize"))

    // Verify that operations on other clients are blocked
    val describeFuture = createAdminClient.describeCluster().clusterId()
    assertFalse(describeFuture.isDone)

    // Release the semaphore and verify that all requests complete
    testSemaphore.release(aclFutures.size)
    assertNotNull(describeFuture.get(10, TimeUnit.SECONDS))
    aclFutures.foreach(_.all().get())
  }

  /**
   * Verify that ACL updates using an asynchronous authorizer are completed asynchronously
   * using a purgatory, enabling other requests to be processed even when ACL updates are blocked.
   */
  @Test
  def testAsynchronousAuthorizerAclUpdatesDontBlockRequestThreads(): Unit = {
    SslAdminIntegrationTest.executor = Some(Executors.newSingleThreadExecutor)
    val testSemaphore = new Semaphore(0)
    SslAdminIntegrationTest.semaphore = Some(testSemaphore)

    waitForNoBlockedRequestThreads()

    val aclFutures = (0 until numRequestThreads).map(_ => createAdminClient.createAcls(List(acl2).asJava))
    waitForNoBlockedRequestThreads()
    assertTrue(aclFutures.forall(future => !future.all.isDone))
    // Other requests should succeed even though ACL updates are blocked
    assertNotNull(createAdminClient.describeCluster().clusterId().get(10, TimeUnit.SECONDS))
    TestUtils.waitUntilTrue(() => purgatoryMetric("PurgatorySize") > 0, "PurgatorySize metrics not updated")
    TestUtils.waitUntilTrue(() => purgatoryMetric("NumDelayedOperations") > 0, "NumDelayedOperations metrics not updated")

    // Release the semaphore and verify that ACL update requests complete
    testSemaphore.release(aclFutures.size)
    aclFutures.foreach(_.all.get())
    assertEquals(0, purgatoryMetric("NumDelayedOperations"))
  }

  private def verifyAclUpdates(): Unit = {
    val acl = new AclBinding(new ResourcePattern(ResourceType.TOPIC, "mytopic3", PatternType.LITERAL),
      new AccessControlEntry("User:ANONYMOUS", "*", AclOperation.DESCRIBE, AclPermissionType.ALLOW))

    def validateRequestContext(context: AuthorizableRequestContext, apiKey: ApiKeys): Unit = {
      assertEquals(SecurityProtocol.SSL, context.securityProtocol)
      assertEquals("SSL", context.listenerName)
      assertEquals(KafkaPrincipal.ANONYMOUS, context.principal)
      assertEquals(apiKey.id.toInt, context.requestType)
      assertEquals(apiKey.latestVersion.toInt, context.requestVersion)
      assertTrue(s"Invalid correlation id: ${context.correlationId}", context.correlationId > 0)
      assertTrue(s"Invalid client id: ${context.clientId}", context.clientId.startsWith("adminclient"))
      assertTrue(s"Invalid host address: ${context.clientAddress}", context.clientAddress.isLoopbackAddress)
    }

    val testSemaphore = new Semaphore(0)
    SslAdminIntegrationTest.semaphore = Some(testSemaphore)

    client = AdminClient.create(createConfig())
    val results = client.createAcls(List(acl2, acl3).asJava).values
    assertEquals(Set(acl2, acl3), results.keySet().asScala)
    assertFalse(results.values().asScala.exists(_.isDone))
    TestUtils.waitUntilTrue(() => testSemaphore.hasQueuedThreads, "Authorizer not blocked in createAcls")
    testSemaphore.release()
    results.values().asScala.foreach(_.get)
    validateRequestContext(SslAdminIntegrationTest.lastUpdateRequestContext.get, ApiKeys.CREATE_ACLS)

    testSemaphore.acquire()
    val results2 = client.deleteAcls(List(acl.toFilter, acl2.toFilter, acl3.toFilter).asJava).values
    assertEquals(Set(acl.toFilter, acl2.toFilter, acl3.toFilter), results2.keySet.asScala)
    assertFalse(results2.values().asScala.exists(_.isDone))
    TestUtils.waitUntilTrue(() => testSemaphore.hasQueuedThreads, "Authorizer not blocked in deleteAcls")
    testSemaphore.release()
    results.values().asScala.foreach(_.get)
    assertEquals(0, results2.get(acl.toFilter).get.values.size())
    assertEquals(Set(acl2), results2.get(acl2.toFilter).get.values.asScala.map(_.binding).toSet)
    assertEquals(Set(acl3), results2.get(acl3.toFilter).get.values.asScala.map(_.binding).toSet)
    validateRequestContext(SslAdminIntegrationTest.lastUpdateRequestContext.get, ApiKeys.DELETE_ACLS)
  }

  private def createAdminClient: AdminClient = {
    val config = createConfig()
    config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "40000")
    val client = AdminClient.create(config)
    adminClients += client
    client
  }

  private def blockedRequestThreads: List[Thread] = {
    val requestThreads = Thread.getAllStackTraces.keySet.asScala
      .filter(_.getName.contains("data-plane-kafka-request-handler"))
    assertEquals(numRequestThreads, requestThreads.size)
    requestThreads.filter(_.getState == Thread.State.WAITING).toList
  }

  private def numRequestThreads = servers.head.config.numIoThreads * servers.size

  private def waitForNoBlockedRequestThreads(): Unit = {
    val (blockedThreads, _) = TestUtils.computeUntilTrue(blockedRequestThreads)(_.isEmpty)
    assertEquals(List.empty, blockedThreads)
  }

  private def purgatoryMetric(name: String): Int = {
    val allMetrics = Metrics.defaultRegistry.allMetrics.asScala
    val metrics = allMetrics.filter { case (metricName, _) =>
      metricName.getMBeanName.contains("delayedOperation=AlterAcls") && metricName.getMBeanName.contains(s"name=$name")
    }.values.toList
    assertTrue(s"Unable to find metric $name: allMetrics: ${allMetrics.keySet.map(_.getMBeanName)}", metrics.nonEmpty)
    metrics.map(_.asInstanceOf[Gauge[Int]].value).sum
  }
}
