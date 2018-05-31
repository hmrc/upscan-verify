/*
 * Copyright 2018 HM Revenue & Customs
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

import java.io.{ByteArrayInputStream, InputStream}
import java.time.Instant

import com.amazonaws.AmazonServiceException
import model._
import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.Mockito
import org.mockito.Mockito.{never, verify, verifyNoMoreInteractions, when}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{GivenWhenThen, Matchers}
import uk.gov.hmrc.clamav.{ClamAntiVirus, ClamAntiVirusFactory}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ScanUploadedFilesFlowSpec extends UnitSpec with Matchers with GivenWhenThen with MockitoSugar {

  val parser = new MessageParser {
    override def parse(message: Message) = message.body match {
      case "VALID-BODY" => Future.successful(FileUploadEvent(S3ObjectLocation("bucket", message.id)))
      case _            => Future.failed(new Exception("Invalid body"))
    }
  }

  "ScanUploadedFilesFlow" should {
    "get messages from the queue consumer, and scan and post-process valid messages" in {
      Given("there are only valid messages in a message queue")
      val validMessage = Message("ID", "VALID-BODY", "RECEIPT-1")
      val s3object     = S3ObjectLocation("bucket", "ID")

      val queueConsumer = mock[QueueConsumer]
      when(queueConsumer.poll()).thenReturn(List(validMessage))
      when(queueConsumer.confirm(any())).thenReturn(Future.successful(()))

      val fileManager = mock[FileManager]
      when(fileManager.getObjectContent(s3object))
        .thenReturn(Future.successful(StubObjectContent(mock[InputStream], 0)))
      when(fileManager.getObjectMetadata(s3object)).thenReturn(
        Future.successful(ObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), Instant.now)))

      val fileCheckingService = mock[FileCheckingService]
      when(fileCheckingService.check(any(), any()))
        .thenReturn(Future.successful(ValidFileCheckingResult(s3object)))

      val scanningResultHandler = mock[FileCheckingResultHandler]
      when(scanningResultHandler.handleCheckingResult(any())).thenReturn(Future.successful(SafeToContinue))

      val instanceTerminator = mock[InstanceTerminator]
      val queueOrchestrator =
        new ScanUploadedFilesFlow(
          queueConsumer,
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          instanceTerminator)

      When("the orchestrator is called")
      Await.result(queueOrchestrator.run(), 30 seconds)

      Then("the queue consumer should poll for messages")
      verify(queueConsumer).poll()

      And("scanning result handler is called")
      verify(scanningResultHandler).handleCheckingResult(ValidFileCheckingResult(s3object))

      And("successfully processed messages are confirmed")
      verify(queueConsumer).confirm(validMessage)
    }

    "terminate instance if virus found" in {
      Given("there are only valid messages in a message queue")
      val validMessage = Message("ID", "VALID-BODY", "RECEIPT-1")
      val s3object     = S3ObjectLocation("bucket", "ID")

      val queueConsumer = mock[QueueConsumer]
      when(queueConsumer.poll()).thenReturn(List(validMessage))
      when(queueConsumer.confirm(any())).thenReturn(Future.successful(()))

      val fileManager = mock[FileManager]
      when(fileManager.getObjectMetadata(s3object))
        .thenReturn(Future.successful(ObjectMetadata(Map.empty, Instant.now)))

      val fileCheckingService = mock[FileCheckingService]
      when(fileCheckingService.check(any(), any()))
        .thenReturn(Future.successful(FileInfectedCheckingResult(s3object, "Virus name")))

      val scanningResultHandler = mock[FileCheckingResultHandler]
      when(scanningResultHandler.handleCheckingResult(any())).thenReturn(Future.successful(ShouldTerminate))

      val instanceTerminator = mock[InstanceTerminator]
      when(instanceTerminator.terminate()).thenReturn(Future(()))

      val queueOrchestrator =
        new ScanUploadedFilesFlow(
          queueConsumer,
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          instanceTerminator)

      When("the orchestrator is called")
      Await.result(queueOrchestrator.run(), 30 seconds)

      Then("the queue consumer should poll for messages")
      verify(queueConsumer).poll()

      And("scanning result handler is called")
      verify(scanningResultHandler)
        .handleCheckingResult(FileInfectedCheckingResult(s3object, "Virus name"))

      And("successfully processed messages are confirmed")
      And("instance is terminated")
      val inOrder = Mockito.inOrder(queueConsumer, instanceTerminator)

      inOrder.verify(queueConsumer).confirm(validMessage)
      inOrder.verify(instanceTerminator).terminate()

    }

    "get messages from the queue consumer, and perform scanning for valid messages and ignore invalid messages" in {
      Given("there are only valid messages in a message queue")
      val validMessage1  = Message("ID1", "VALID-BODY", "RECEIPT-1")
      val invalidMessage = Message("ID2", "INVALID-BODY", "RECEIPT-2")
      val validMessage2  = Message("ID3", "VALID-BODY", "RECEIPT-3")

      val s3object1 = S3ObjectLocation("bucket", "ID1")
      val s3object3 = S3ObjectLocation("bucket", "ID3")

      val queueConsumer = mock[QueueConsumer]
      when(queueConsumer.poll()).thenReturn(List(validMessage1, invalidMessage, validMessage2))
      when(queueConsumer.confirm(any()))
        .thenReturn(Future.successful(()))
        .thenReturn(Future.successful(()))

      val fileManager = mock[FileManager]
      when(fileManager.getObjectMetadata(any())).thenReturn(Future.successful(ObjectMetadata(Map.empty, Instant.now)))

      val fileCheckingService = mock[FileCheckingService]
      when(fileCheckingService.check(meq(s3object1), any()))
        .thenReturn(Future.successful(ValidFileCheckingResult(s3object1)))
      when(fileCheckingService.check(meq(s3object3), any()))
        .thenReturn(Future.successful(FileInfectedCheckingResult(s3object3, "infection")))

      val scanningResultHandler = mock[FileCheckingResultHandler]
      when(scanningResultHandler.handleCheckingResult(ValidFileCheckingResult(s3object1)))
        .thenReturn(Future.successful(SafeToContinue))

      when(scanningResultHandler.handleCheckingResult(FileInfectedCheckingResult(s3object3, "infection")))
        .thenReturn(Future.successful(ShouldTerminate))

      val instanceTerminator = mock[InstanceTerminator]
      when(instanceTerminator.terminate()).thenReturn(Future(()))

      val queueOrchestrator =
        new ScanUploadedFilesFlow(
          queueConsumer,
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          instanceTerminator)

      When("the orchestrator is called")
      Await.result(queueOrchestrator.run(), 30 seconds)

      Then("the queue consumer should poll for messages")
      verify(queueConsumer).poll()

      And("notification service is called only for valid messages")
      verify(scanningResultHandler).handleCheckingResult(ValidFileCheckingResult(s3object1))
      verify(scanningResultHandler).handleCheckingResult(FileInfectedCheckingResult(s3object3, "infection"))
      verifyNoMoreInteractions(scanningResultHandler)

      And("successfully processed messages are confirmed")
      verify(queueConsumer).confirm(validMessage1)
      verify(queueConsumer).confirm(validMessage2)

      And("invalid messages are not confirmed")
      verifyNoMoreInteractions(queueConsumer)

    }

    "do not confirm valid messages for which scanning has failed" in {

      val s3object1 = S3ObjectLocation("bucket", "ID1")
      val s3object2 = S3ObjectLocation("bucket", "ID2")
      val s3object3 = S3ObjectLocation("bucket", "ID3")

      Given("there are only valid messages in a message queue")
      val validMessage1 = Message("ID1", "VALID-BODY", "RECEIPT-1")
      val validMessage2 = Message("ID2", "VALID-BODY", "RECEIPT-2")
      val validMessage3 = Message("ID3", "VALID-BODY", "RECEIPT-3")

      val queueConsumer = mock[QueueConsumer]
      when(queueConsumer.poll()).thenReturn(List(validMessage1, validMessage2, validMessage3))
      when(queueConsumer.confirm(any()))
        .thenReturn(Future.successful(()))
        .thenReturn(Future.successful(()))

      val fileManager = mock[FileManager]
      when(fileManager.getObjectMetadata(any())).thenReturn(Future.successful(ObjectMetadata(Map.empty, Instant.now)))

      val fileCheckingService = mock[FileCheckingService]
      when(fileCheckingService.check(meq(s3object1), any()))
        .thenReturn(Future.successful(ValidFileCheckingResult(s3object1)))
      when(fileCheckingService.check(meq(s3object2), any()))
        .thenReturn(Future.failed(new Exception("Planned exception")))

      when(fileCheckingService.check(meq(s3object3), any()))
        .thenReturn(Future.successful(FileInfectedCheckingResult(s3object3, "infection")))

      val scanningResultHandler = mock[FileCheckingResultHandler]
      when(scanningResultHandler.handleCheckingResult(any())).thenReturn(Future.successful(SafeToContinue))

      val instanceTerminator = mock[InstanceTerminator]

      val queueOrchestrator =
        new ScanUploadedFilesFlow(
          queueConsumer,
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          instanceTerminator)

      When("the orchestrator is called")
      Await.result(queueOrchestrator.run(), 30 seconds)

      Then("the queue consumer should poll for messages")
      verify(queueConsumer).poll()

      And("scanning handler is called only for successfully scanned messages")
      verify(scanningResultHandler).handleCheckingResult(ValidFileCheckingResult(s3object1))
      verify(scanningResultHandler).handleCheckingResult(FileInfectedCheckingResult(s3object3, "infection"))
      verifyNoMoreInteractions(scanningResultHandler)

      And("successfully processed messages are confirmed")
      verify(queueConsumer).confirm(validMessage1)
      verify(queueConsumer).confirm(validMessage3)

      And("invalid messages are not confirmed")
      verifyNoMoreInteractions(queueConsumer)

    }

    "skip processing if file metadata is unavailable" in {
      val s3object1 = S3ObjectLocation("bucket", "ID1")
      val s3object2 = S3ObjectLocation("bucket", "ID2")
      val s3object3 = S3ObjectLocation("bucket", "ID3")

      val client = mock[ClamAntiVirus]

      val factory = mock[ClamAntiVirusFactory]
      Mockito.when(factory.getClient()).thenReturn(client)

      Given("there are only valid messages in a message queue")
      val validMessage1 = Message("ID1", "VALID-BODY", "RECEIPT-1")
      val validMessage2 = Message("ID2", "VALID-BODY", "RECEIPT-2")
      val validMessage3 = Message("ID3", "VALID-BODY", "RECEIPT-3")

      val queueConsumer = mock[QueueConsumer]
      when(queueConsumer.poll()).thenReturn(List(validMessage1, validMessage2, validMessage3))
      when(queueConsumer.confirm(any())).thenReturn(Future.successful(()))

      val fileContentsAsBytes = "FileContents".getBytes
      val stringInputStream   = new ByteArrayInputStream(fileContentsAsBytes)

      And("the fileManager fails to return file metadata for the 2nd message")
      val fileManager = mock[FileManager]
      when(fileManager.getObjectMetadata(s3object1))
        .thenReturn(Future.successful(ObjectMetadata(Map.empty, Instant.now)))
      when(fileManager.getObjectMetadata(s3object2))
        .thenReturn(Future.failed(new AmazonServiceException("Expected exception")))
      when(fileManager.getObjectMetadata(s3object3))
        .thenReturn(Future.successful(ObjectMetadata(Map.empty, Instant.now)))

      val fileCheckingService = mock[FileCheckingService]
      when(fileCheckingService.check(meq(s3object1), any()))
        .thenReturn(Future.successful(ValidFileCheckingResult(s3object1)))
      when(fileCheckingService.check(meq(s3object3), any()))
        .thenReturn(Future.successful(ValidFileCheckingResult(s3object3)))

      val scanningResultHandler = mock[FileCheckingResultHandler]
      when(scanningResultHandler.handleCheckingResult(ValidFileCheckingResult(s3object1)))
        .thenReturn(Future.successful(SafeToContinue))
      when(scanningResultHandler.handleCheckingResult(ValidFileCheckingResult(s3object3)))
        .thenReturn(Future.successful(SafeToContinue))

      val instanceTerminator = mock[InstanceTerminator]

      val queueOrchestrator =
        new ScanUploadedFilesFlow(
          queueConsumer,
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          instanceTerminator)

      When("the orchestrator is called")
      Await.result(queueOrchestrator.run(), 30 seconds)

      Then("the queue consumer should poll for messages")
      verify(queueConsumer).poll()

      And("the subsequent components should be invoked for 1st and 3rd messages")
      verify(fileCheckingService).check(meq(s3object1), any())
      verify(fileCheckingService).check(meq(s3object3), any())

      verify(scanningResultHandler).handleCheckingResult(ValidFileCheckingResult(s3object1))
      verify(scanningResultHandler).handleCheckingResult(ValidFileCheckingResult(s3object3))

      verify(queueConsumer).confirm(validMessage1)
      verify(queueConsumer).confirm(validMessage3)

      And("the subsequent components should not be invoked for the 2nd message")
      verify(fileCheckingService, never()).check(meq(s3object2), any())
      verify(scanningResultHandler, never()).handleCheckingResult(ValidFileCheckingResult(s3object2))
      verify(queueConsumer, never()).confirm(validMessage2)

      verifyNoMoreInteractions(fileCheckingService, scanningResultHandler, queueConsumer, instanceTerminator)
    }
  }
}
