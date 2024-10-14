/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.upscanverify.connector.aws

import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{atLeast => atLeastTimes, times, when, verify}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{ChangeMessageVisibilityRequest, ChangeMessageVisibilityResponse, DeleteMessageRequest, DeleteMessageResponse, Message, ReceiveMessageRequest, ReceiveMessageResponse}
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration
import uk.gov.hmrc.upscanverify.test.UnitSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.jdk.FutureConverters._

class SqsConsumerSpec
  extends UnitSpec
     with Eventually
     with IntegrationPatience
     with BeforeAndAfterEach:

  var actorSystem: ActorSystem = null
  import ExecutionContext.Implicits.global

  val queueUrl = "queueUrl"
  val serviceConfiguration = mock[ServiceConfiguration]
  when (serviceConfiguration.inboundQueueUrl)
    .thenReturn(queueUrl)
  when(serviceConfiguration.inboundQueueVisibilityTimeout)
    .thenReturn(20.seconds)
  when(serviceConfiguration.retryInterval)
    .thenReturn(2.seconds)
  when(serviceConfiguration.waitTime)
    .thenReturn(20.seconds)

  "SqsConsumer" should:
    "continuously poll the queue" in:
      given ActorSystem = actorSystem

      val callCount = AtomicInteger(0)

      val pollingJob: PollingJob =
        new PollingJob:
          override def processMessage(message: Message): Future[Unit] =
            callCount.incrementAndGet()
            Future.unit

      val sqsClient = mock[SqsAsyncClient]
      when(sqsClient.deleteMessage(any[DeleteMessageRequest]))
        .thenReturn(Future.successful(DeleteMessageResponse.builder().build()).asJava)
      when(sqsClient.receiveMessage(any[ReceiveMessageRequest]))
        .thenReturn:
          Future
            .successful:
              ReceiveMessageResponse.builder().messages(Message.builder().build()).build()
            .asJava

      SqsConsumer(sqsClient, pollingJob, serviceConfiguration)

      eventually:
        callCount.get() should be > 5

      // just assert > 4 - the final one may not have been deleted yet
      verify(sqsClient, atLeastTimes(4)).deleteMessage(any[DeleteMessageRequest])

    "recover from failure" in:
      given ActorSystem = actorSystem

      val genId     = AtomicInteger(0)
      val callCount = AtomicInteger(0)

      val pollingJob: PollingJob =
        new PollingJob:
          override def processMessage(message: Message): Future[Unit] =
            callCount.incrementAndGet()
            if message.receiptHandle == "2" then
              Future.failed(RuntimeException("Planned failure"))
            else
              Future.unit

      val sqsClient = mock[SqsAsyncClient]
      when(sqsClient.deleteMessage(any[DeleteMessageRequest]))
        .thenReturn(Future.successful(DeleteMessageResponse.builder().build()).asJava)
      when(sqsClient.changeMessageVisibility(any[ChangeMessageVisibilityRequest]))
        .thenReturn(Future.successful(ChangeMessageVisibilityResponse.builder().build()).asJava)
      when(sqsClient.receiveMessage(any[ReceiveMessageRequest]))
        .thenAnswer: _ =>
          Future
            .successful:
              ReceiveMessageResponse
                .builder()
                .messages:
                  Message
                    .builder()
                    .receiptHandle(genId.incrementAndGet().toString)
                    .build()
                .build()
            .asJava


      SqsConsumer(sqsClient, pollingJob, serviceConfiguration)

      eventually:
        callCount.get() should be > 5

      // final one may not have been deleted yet
      verify(sqsClient, atLeastTimes(3)).deleteMessage(any[DeleteMessageRequest])

      //specifically
      verify(sqsClient).deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle("1").build())
      verify(sqsClient).deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle("3").build())
      // but not
      verify(sqsClient, times(0)).deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle("2").build())
      // instead
      verify(sqsClient).changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(queueUrl).receiptHandle("2").visibilityTimeout(serviceConfiguration.retryInterval.toSeconds.toInt).build())


  override def beforeEach(): Unit =
    actorSystem = ActorSystem()

  override def afterEach(): Unit =
    actorSystem.terminate()
