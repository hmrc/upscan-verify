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

import com.amazonaws.services.sqs.model.Message
import com.codahale.metrics.MetricRegistry
import play.api.Logging
import uk.gov.hmrc.upscanverify.connector.aws.PollingJob
import uk.gov.hmrc.upscanverify.util.logging.LoggingUtils

import java.time.{Clock, Instant}
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

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
        s"Received message with id: [${sqsMessage.getMessageId}] and receiptHandle: [${sqsMessage.getReceiptHandle}], message details:\n "
          + sqsMessage.toString

    val queueTimestamp = sqsMessage.getAttributes.asScala.get("SentTimestamp").map(s => Instant.ofEpochMilli(s.toLong))

    if queueTimestamp.isEmpty then
      logger.warn(s"SentTimestamp is missing from the message attribute. Message id = ${sqsMessage.getMessageId}")

    uk.gov.hmrc.upscanverify.model.Message(
      sqsMessage.getMessageId,
      sqsMessage.getBody,
      sqsMessage.getReceiptHandle,
      receivedAt,
      queueTimestamp
    )

  override def processMessage(sqsMessage: Message): Future[Unit] =
    val message = toUpscanMessage(sqsMessage)
    messageParser.parse(message)
      .flatMap: parsedMessage =>
        val context = MessageContext(parsedMessage.location.objectKey)
        LoggingUtils.withMdc(context):
          logger.info(s"Created FileUploadEvent for Key=[${parsedMessage.location.objectKey}].")
          messageProcessor.processMessage(parsedMessage, message)
            .map: _ =>
              metricRegistry.meter("verifyThroughput").mark()
            .recover:
              case exception =>
                logger.error(s"Failed to process message '${message.id}', cause ${exception.getMessage}", exception)
