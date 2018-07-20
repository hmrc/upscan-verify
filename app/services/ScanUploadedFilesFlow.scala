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

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.implicits._
import com.kenshoo.play.metrics.Metrics
import javax.inject.Inject
import model.Message
import util.logging.LoggingDetails
import utils.MonadicUtils._

import scala.concurrent.ExecutionContext

case class MessageContext(fileReference: String)

case class ExceptionWithContext(e: Exception, context: Option[MessageContext])

class ScanUploadedFilesFlow @Inject()(
  parser: MessageParser,
  fileManager: FileManager,
  fileCheckingService: FileCheckingService,
  scanningResultHandler: FileCheckingResultHandler,
  metrics: Metrics)(implicit ec: ExecutionContext)
    extends MessageProcessor {

  def processMessage(message: Message): FutureEitherWithContext[MessageContext] = {
    val messageProcessingStartTime = System.currentTimeMillis

    for {
      parsedMessage <- withoutContext(parser.parse(message))
      context = MessageContext(parsedMessage.location.objectKey)
      ld      = LoggingDetails.fromMessageContext(context)
      metadata <- withContext(fileManager.getObjectMetadata(parsedMessage.location)(ld), context)
      inboundObjectDetails = InboundObjectDetails(metadata, parsedMessage.clientIp, parsedMessage.location)
      scanningResult <- withContext(fileCheckingService.check(parsedMessage.location, metadata)(ld), context)
      _ = addMetrics(metadata.uploadedTimestamp, messageProcessingStartTime, System.currentTimeMillis)
      _ <- withContext(scanningResultHandler.handleCheckingResult(inboundObjectDetails, scanningResult)(ld), context)
    } yield context

  }

  private def addMetrics(uploadedTimestamp: Instant, startTimeMilliseconds: Long, endTimeMilliseconds: Long): Unit = {
    addUploadToStartProcessMetrics(uploadedTimestamp, startTimeMilliseconds)
    addUploadToEndScanMetrics(uploadedTimestamp, endTimeMilliseconds)
    addInternalProcessMetrics(startTimeMilliseconds, endTimeMilliseconds)
  }

  private def addInternalProcessMetrics(startTimeMilliseconds: Long, endTimeMilliseconds: Long): Unit = {
    val interval = endTimeMilliseconds - startTimeMilliseconds
    metrics.defaultRegistry.timer("upscanVerifyProcessing").update(interval, TimeUnit.MILLISECONDS)
  }

  private def addUploadToStartProcessMetrics(uploadedTimestamp: Instant, startTimeMilliseconds: Long): Unit = {
    val interval = startTimeMilliseconds - uploadedTimestamp.toEpochMilli
    metrics.defaultRegistry.timer("uploadToStartProcessing").update(interval, TimeUnit.MILLISECONDS)
  }

  private def addUploadToEndScanMetrics(uploadedTimestamp: Instant, endTimeMilliseconds: Long): Unit = {
    val interval = endTimeMilliseconds - uploadedTimestamp.toEpochMilli
    metrics.defaultRegistry.timer("uploadToScanComplete").update(interval, TimeUnit.MILLISECONDS)
  }
}
