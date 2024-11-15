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

package uk.gov.hmrc.upscanverify.service

import com.codahale.metrics.MetricRegistry
import play.api.Logging
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.sqs.model.{Message, MessageSystemAttributeName}
import uk.gov.hmrc.upscanverify.connector.aws.PollingJob
import uk.gov.hmrc.upscanverify.util.logging.LoggingUtils

import java.time.{Clock, Instant}
import java.util.concurrent.CompletionException
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class QueueProcessingJob @Inject()(
  messageProcessor: MessageProcessor,
  messageParser   : MessageParser,
  metricRegistry  : MetricRegistry,
  clock           : Clock
)(using
  ExecutionContext
) extends PollingJob
     with Logging:

  private def toUpscanMessage(sqsMessage: Message): uk.gov.hmrc.upscanverify.model.Message =
    val receivedAt = clock.instant()

    if logger.isDebugEnabled then
      logger.debug:
        s"Received message with id: [${sqsMessage.messageId}] and receiptHandle: [${sqsMessage.receiptHandle}], message details:\n "
          + sqsMessage.toString

    val queueTimestamp = sqsMessage.attributes.asScala.get(MessageSystemAttributeName.SENT_TIMESTAMP).map(s => Instant.ofEpochMilli(s.toLong))

    if queueTimestamp.isEmpty then
      logger.warn(s"SentTimestamp is missing from the message attribute. Message id = ${sqsMessage.messageId}")

    uk.gov.hmrc.upscanverify.model.Message(
      sqsMessage.messageId,
      sqsMessage.body,
      sqsMessage.receiptHandle,
      receivedAt,
      queueTimestamp
    )

  override def processMessage(sqsMessage: Message): Future[Boolean] =
    val message = toUpscanMessage(sqsMessage)
    messageParser.parse(message)
      .flatMap: parsedMessage =>
        val fileReference = parsedMessage.location.objectKey
        LoggingUtils.withMdc(Map("file-reference" -> fileReference)):
          logger.info(s"Created FileUploadEvent for Key=[$fileReference].")
          messageProcessor.processMessage(parsedMessage, message)
            .map: _ =>
              metricRegistry.meter("verifyThroughput").mark()
              true
            .recover:
              case exception: CompletionException if exception.getCause.isInstanceOf[NoSuchKeyException] =>
                logger.warn(s"Skipped processing message '${message.id}', because it was not found in the inbound bucket (already processed)")
                true
              case exception =>
                logger.error(s"Failed to process message '${message.id}', cause ${exception.getMessage}", exception)
                false
