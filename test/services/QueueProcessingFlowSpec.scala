/*
 * Copyright 2020 HM Revenue & Customs
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

package services

import java.time.Instant

import model.Message
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verify, verifyNoMoreInteractions, when}
import org.scalatest.{GivenWhenThen, Matchers}
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.play.test.UnitSpec
import utils.MonadicUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class QueueProcessingFlowSpec extends UnitSpec with Matchers with GivenWhenThen with MockitoSugar {

  "get messages from the queue consumer, and confirm successfully processed and do not confirm unsuccessfully processed" in {

    val queueConsumer    = mock[QueueConsumer]
    val messageProcessor = mock[MessageProcessor]
    val queueProcessingFlow =
      new QueueProcessingJob(queueConsumer, messageProcessor)

    Given("there are three message in a message queue")
    val validMessage1  = Message("ID1", "VALID-BODY", "RECEIPT-1", Instant.now())
    val invalidMessage = Message("ID2", "INVALID-BODY", "RECEIPT-2", Instant.now())
    val validMessage2  = Message("ID3", "VALID-BODY", "RECEIPT-3", Instant.now())

    when(queueConsumer.poll()).thenReturn(List(validMessage1, invalidMessage, validMessage2))
    when(queueConsumer.confirm(any()))
      .thenReturn(Future.successful(()))
      .thenReturn(Future.successful(()))

    And("processing of two messages succeeds")
    val context = MessageContext("TEST")
    when(messageProcessor.processMessage(validMessage1))
      .thenReturn(MonadicUtils.withContext(Future.successful(context), context))

    when(messageProcessor.processMessage(validMessage2))
      .thenReturn(MonadicUtils.withContext(Future.successful(context), context))

    when(messageProcessor.processMessage(invalidMessage))
      .thenReturn(MonadicUtils.withContext[MessageContext](Future.failed(new RuntimeException("Exception")), context))

    And("processing of one message fails")

    When("the orchestrator is called")
    Await.result(queueProcessingFlow.run(), 30 seconds)

    Then("the queue consumer should poll for messages")
    verify(queueConsumer).poll()

    And("all messages should be processed")
    verify(messageProcessor).processMessage(validMessage1)
    verify(messageProcessor).processMessage(invalidMessage)
    verify(messageProcessor).processMessage(validMessage2)

    And("successfull  y processed messages should be confirmed")
    verify(queueConsumer).confirm(validMessage1)
    verify(queueConsumer).confirm(validMessage2)

    And("invalid messages are not confirmed")
    verifyNoMoreInteractions(queueConsumer)

  }

}
