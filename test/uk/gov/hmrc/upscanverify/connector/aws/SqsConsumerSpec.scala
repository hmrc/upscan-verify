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

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{ChangeMessageVisibilityRequest, DeleteMessageRequest, Message, ReceiveMessageRequest, ReceiveMessageResult}
import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{atLeast => atLeastTimes, times, when, verify}
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.Eventually
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration
import uk.gov.hmrc.upscanverify.test.UnitSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

class SqsConsumerSpec
  extends UnitSpec
     with Eventually
     with BeforeAndAfter:

  given actorSystem: ActorSystem = ActorSystem()
  import ExecutionContext.Implicits.global

  val queueUrl = "queueUrl"
  val serviceConfiguration = mock[ServiceConfiguration]
  when (serviceConfiguration.inboundQueueUrl)
    .thenReturn(queueUrl)
  when(serviceConfiguration.inboundQueueVisibilityTimeout)
    .thenReturn(20.seconds)
  when(serviceConfiguration.retryInterval)
    .thenReturn(2.seconds)

  "SqsConsumer" should:
    "continuously poll the queue" in:
      val callCount = AtomicInteger(0)

      val pollingJob: PollingJob =
        new PollingJob:
          override def processMessage(message: Message): Future[Unit] =
            callCount.incrementAndGet()
            Future.unit

      val sqsClient = mock[AmazonSQS]
      when(sqsClient.receiveMessage(any[ReceiveMessageRequest]))
        .thenReturn(ReceiveMessageResult().withMessages(Message()))

      SqsConsumer(sqsClient, pollingJob, serviceConfiguration)

      eventually:
        callCount.get() should be > 5

      // just assert > 4 - the final one may not have been deleted yet
      verify(sqsClient, atLeastTimes(4)).deleteMessage(any[DeleteMessageRequest])

    "recover from failure" in:
      val genId     = AtomicInteger(0)
      val callCount = AtomicInteger(0)

      val pollingJob: PollingJob =
        new PollingJob:
          override def processMessage(message: Message): Future[Unit] =
            callCount.incrementAndGet()
            if message.getReceiptHandle == "2" then
              Future.failed(RuntimeException("Planned failure"))
            else
              Future.unit

      val sqsClient = mock[AmazonSQS]
      when(sqsClient.receiveMessage(any[ReceiveMessageRequest]))
        .thenAnswer: _ =>
          ReceiveMessageResult().withMessages(Message().withReceiptHandle(genId.incrementAndGet().toString))

      SqsConsumer(sqsClient, pollingJob, serviceConfiguration)

      eventually:
        callCount.get() should be > 5

      // final one may not have been deleted yet
      verify(sqsClient, atLeastTimes(3)).deleteMessage(any[DeleteMessageRequest])

      //specifically
      verify(sqsClient).deleteMessage(DeleteMessageRequest(queueUrl, "1"))
      verify(sqsClient).deleteMessage(DeleteMessageRequest(queueUrl, "3"))
      // but not
      verify(sqsClient, times(0)).deleteMessage(DeleteMessageRequest(queueUrl, "2"))
      // instead
      verify(sqsClient).changeMessageVisibility(ChangeMessageVisibilityRequest(queueUrl, "2", serviceConfiguration.retryInterval.toSeconds.toInt))


  after:
    actorSystem.terminate()
