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

import cats.implicits._
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import uk.gov.hmrc.upscanverify.model.Message
import uk.gov.hmrc.upscanverify.util.MonadicUtils._
import uk.gov.hmrc.upscanverify.util.logging.LoggingDetails

import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.ExecutionContext

case class MessageContext(
  fileReference: String
)

case class ExceptionWithContext(
  e      : Exception,
  context: Option[MessageContext]
)

class ScanUploadedFilesFlow @Inject()(
  parser               : MessageParser,
  fileManager          : FileManager,
  fileCheckingService  : FileCheckingService,
  scanningResultHandler: FileCheckingResultHandler,
  metrics              : Metrics,
  clock                : Clock
)(using
  ExecutionContext
) extends MessageProcessor:

  def processMessage(message: Message): FutureEitherWithContext[MessageContext] =
    for
      parsedMessage        <- withoutContext(parser.parse(message))
      context              =  MessageContext(parsedMessage.location.objectKey)
      ld                   =  LoggingDetails.fromMessageContext(context)
      metadata             <- withContext(fileManager.getObjectMetadata(parsedMessage.location)(using ld), context)
      inboundObjectDetails =  InboundObjectDetails(metadata, parsedMessage.clientIp, parsedMessage.location)
      scanningResult       <- withContext(fileCheckingService.check(parsedMessage.location, metadata)(using ld), context)
      _                    <- withContext(scanningResultHandler.handleCheckingResult(inboundObjectDetails, scanningResult, message.receivedAt)(using ld), context)
      _                    =  addMetrics(metadata.uploadedTimestamp, message)
    yield context

  private def addMetrics(uploadedTimestamp: Instant, message: Message): Unit =
    val endTime = clock.instant()
    addUploadToStartProcessMetrics(uploadedTimestamp, message.receivedAt)
    addUploadToEndScanMetrics(uploadedTimestamp, endTime)
    addInternalProcessMetrics(message.receivedAt, endTime)
    message.queueTimeStamp.foreach: ts =>
      addQueueSentToStartProcessMetrics(ts, message.receivedAt)

  private def addUploadToStartProcessMetrics(uploadedTimestamp: Instant, startTime: Instant): Unit =
    val interval = startTime.toEpochMilli - uploadedTimestamp.toEpochMilli
    metrics.defaultRegistry.timer("uploadToStartProcessing").update(interval, TimeUnit.MILLISECONDS)

  private def addQueueSentToStartProcessMetrics(queueTimestamp: Instant, startTime: Instant): Unit =
    val interval = startTime.toEpochMilli - queueTimestamp.toEpochMilli
    metrics.defaultRegistry.timer("queueSentToStartProcessing").update(interval, TimeUnit.MILLISECONDS)

  private def addUploadToEndScanMetrics(uploadedTimestamp: Instant, endTime: Instant): Unit =
    val interval = endTime.toEpochMilli - uploadedTimestamp.toEpochMilli
    metrics.defaultRegistry.timer("uploadToScanComplete").update(interval, TimeUnit.MILLISECONDS)

  private def addInternalProcessMetrics(startTime: Instant, endTime: Instant): Unit =
    val interval = endTime.toEpochMilli - startTime.toEpochMilli
    metrics.defaultRegistry.timer("upscanVerifyProcessing").update(interval, TimeUnit.MILLISECONDS)
