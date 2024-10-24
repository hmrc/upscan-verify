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
import uk.gov.hmrc.upscanverify.model.{FileUploadEvent, Message}

import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ScanUploadedFilesFlow @Inject()(
  fileManager          : FileManager,
  fileCheckingService  : FileCheckingService,
  scanningResultHandler: FileCheckingResultHandler,
  metricRegistry       : MetricRegistry,
  clock                : Clock
)(using
  ExecutionContext
) extends MessageProcessor:

  def processMessage(fileUploadEvent: FileUploadEvent, message: Message): Future[Unit] =
    for
      metadata             <- fileManager.getObjectMetadata(fileUploadEvent.location)
      inboundObjectDetails =  InboundObjectDetails(metadata, fileUploadEvent.clientIp, fileUploadEvent.location)
      scanningResult       <- fileCheckingService.check(fileUploadEvent.location, metadata)
      _                    <- scanningResultHandler.handleCheckingResult(inboundObjectDetails, scanningResult, message.receivedAt)
      _                    =  addMetrics(metadata.uploadedTimestamp, message)
    yield ()

  private def addMetrics(uploadedTimestamp: Instant, message: Message): Unit =
    val endTime = clock.instant()
    addUploadToStartProcessMetrics(uploadedTimestamp, message.receivedAt)
    addUploadToEndScanMetrics(uploadedTimestamp, endTime)
    addInternalProcessMetrics(message.receivedAt, endTime)
    message.queueTimeStamp.foreach: ts =>
      addQueueSentToStartProcessMetrics(ts, message.receivedAt)

  private def addUploadToStartProcessMetrics(uploadedTimestamp: Instant, startTime: Instant): Unit =
    val interval = startTime.toEpochMilli - uploadedTimestamp.toEpochMilli
    metricRegistry.timer("uploadToStartProcessing").update(interval, TimeUnit.MILLISECONDS)

  private def addQueueSentToStartProcessMetrics(queueTimestamp: Instant, startTime: Instant): Unit =
    val interval = startTime.toEpochMilli - queueTimestamp.toEpochMilli
    metricRegistry.timer("queueSentToStartProcessing").update(interval, TimeUnit.MILLISECONDS)

  private def addUploadToEndScanMetrics(uploadedTimestamp: Instant, endTime: Instant): Unit =
    val interval = endTime.toEpochMilli - uploadedTimestamp.toEpochMilli
    metricRegistry.timer("uploadToScanComplete").update(interval, TimeUnit.MILLISECONDS)

  private def addInternalProcessMetrics(startTime: Instant, endTime: Instant): Unit =
    val interval = endTime.toEpochMilli - startTime.toEpochMilli
    metricRegistry.timer("upscanVerifyProcessing").update(interval, TimeUnit.MILLISECONDS)
