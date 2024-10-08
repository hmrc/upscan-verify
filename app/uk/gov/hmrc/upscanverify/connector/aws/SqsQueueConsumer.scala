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
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, ReceiveMessageRequest, ReceiveMessageResult}
import play.api.Logging
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration
import uk.gov.hmrc.upscanverify.model.Message
import uk.gov.hmrc.upscanverify.service.QueueConsumer

import java.time.{Clock, Instant}
import javax.inject.Inject
import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContext, Future}

class SqsQueueConsumer @Inject()(
  sqsClient    : AmazonSQS,
  configuration: ServiceConfiguration,
  clock        : Clock
)(using
  ExecutionContext
) extends QueueConsumer with Logging:

  override def poll(): Future[List[Message]] =
    val receiveMessageRequest = ReceiveMessageRequest(configuration.inboundQueueUrl)
      .withMaxNumberOfMessages(configuration.processingBatchSize)
      .withWaitTimeSeconds(20)
      .withVisibilityTimeout(configuration.inboundQueueVisibilityTimeout.toSeconds.toInt)
      .withAttributeNames("All")

    val receiveMessageResult: Future[ReceiveMessageResult] =
      Future(sqsClient.receiveMessage(receiveMessageRequest))

    receiveMessageResult.map: result =>
      val receivedAt = clock.instant()

      result.getMessages.asScala.toList.map: sqsMessage =>
        if logger.isDebugEnabled then
          logger.debug(
            s"Received message with id: [${sqsMessage.getMessageId}] and receiptHandle: [${sqsMessage.getReceiptHandle}], message details:\n "
              + sqsMessage.toString
          )

        val queueTimestamp = sqsMessage.getAttributes.asScala.get("SentTimestamp").map(s => Instant.ofEpochMilli(s.toLong))

        if queueTimestamp.isEmpty then
          logger.warn(s"SentTimestamp is missing from the message attribute. Message id = ${sqsMessage.getMessageId}")

        Message(sqsMessage.getMessageId, sqsMessage.getBody, sqsMessage.getReceiptHandle, receivedAt, queueTimestamp)

  override def confirm(message: Message): Future[Unit] =
    val deleteMessageRequest = DeleteMessageRequest(configuration.inboundQueueUrl, message.receiptHandle)
    Future:
      sqsClient.deleteMessage(deleteMessageRequest)
      logger.debug(
        s"Deleted message from Queue: [${configuration.inboundQueueUrl}], for receiptHandle: [${message.receiptHandle}]."
      )
