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
import cats.data.EitherT
import javax.inject.Inject

import com.kenshoo.play.metrics.Metrics
import model.Message
import play.api.Logger
import uk.gov.hmrc.http.logging.LoggingDetails
import util.logging.LoggingDetails
import util.logging.WithLoggingDetails.withLoggingDetails

import scala.concurrent.{ExecutionContext, Future}

case class MessageContext(ld: LoggingDetails)

case class ExceptionWithContext(e: Exception, context: Option[MessageContext])

class ScanUploadedFilesFlow @Inject()(
  consumer: QueueConsumer,
  parser: MessageParser,
  fileManager: FileManager,
  fileCheckingService: FileCheckingService,
  scanningResultHandler: FileCheckingResultHandler,
  ec2InstanceTerminator: InstanceTerminator,
  metrics: Metrics)(implicit ec: ExecutionContext)
    extends PollingJob {

  def run(): Future[Unit] = {
    val outcomes = for {
      messages        <- consumer.poll()
      messageOutcomes <- Future.sequence { messages.map(processMessage) }
    } yield messageOutcomes

    outcomes.map(_ => ())
  }

  private def processMessage(message: Message): Future[Unit] = {
    val messageProcessingStartTime = System.currentTimeMillis

    val outcome = for {
      parsedMessage <- toEitherT(parser.parse(message))(context = None)

      _ <- {
        implicit val context = Some(MessageContext(LoggingDetails.fromS3ObjectLocation(parsedMessage.location)))

        for {
          metadata       <- toEitherT(fileManager.getObjectMetadata(parsedMessage.location))
          scanningResult <- toEitherT(fileCheckingService.check(parsedMessage.location, metadata))
          _              <- toEitherT(addMetrics(metadata.uploadedTimestamp, messageProcessingStartTime, System.currentTimeMillis))
          instanceSafety <- toEitherT(scanningResultHandler.handleCheckingResult(parsedMessage.location, scanningResult, metadata))
          _              <- toEitherT(consumer.confirm(message))
          _              <- toEitherT(terminateIfInstanceNotSafe(instanceSafety))
        } yield ()
      }
    } yield ()

    outcome.value.map {
      case Right(_) =>
        ()
      case Left(ExceptionWithContext(exception, Some(context))) =>
        withLoggingDetails(context.ld) {
          Logger.error(
            s"Failed to process message '${message.id}' for file '${context.ld.mdcData
              .getOrElse("file-reference", "???")}', cause ${exception.getMessage}",
            exception
          )
        }
      case Left(ExceptionWithContext(exception, None)) =>
        Logger.error(s"Failed to process message '${message.id}', cause ${exception.getMessage}", exception)
    }
  }

  private def terminateIfInstanceNotSafe(instanceSafety: InstanceSafety) =
    instanceSafety match {
      case ShouldTerminate => ec2InstanceTerminator.terminate()
      case _               => Future.successful(())
    }

  private def toEitherT[T](f: Future[T])(
    implicit context: Option[MessageContext]): EitherT[Future, ExceptionWithContext, T] = {
    val futureEither: Future[Either[ExceptionWithContext, T]] =
      f.map(Right(_))
        .recover { case error: Exception => Left(ExceptionWithContext(error, context)) }
    EitherT(futureEither)
  }

  private def addMetrics(
    uploadedTimestamp: Instant,
    startTimeMilliseconds: Long,
    endTimeMilliseconds: Long): Future[Unit] =
    for {
      _ <- addUploadToStartProcessMetrics(uploadedTimestamp, startTimeMilliseconds)
      _ <- addUploadToEndScanMetrics(uploadedTimestamp, endTimeMilliseconds)
      _ <- addInternalProcessMetrics(startTimeMilliseconds, endTimeMilliseconds)
    } yield ()

  private def addInternalProcessMetrics(startTimeMilliseconds: Long, endTimeMilliseconds: Long): Future[Unit] =
    Future.successful {
      val interval = endTimeMilliseconds - startTimeMilliseconds
      metrics.defaultRegistry.timer("upscanVerifyProcessing").update(interval, TimeUnit.MILLISECONDS)
    }

  private def addUploadToStartProcessMetrics(uploadedTimestamp: Instant, startTimeMilliseconds: Long): Future[Unit] =
    Future.successful {
      val interval = startTimeMilliseconds - uploadedTimestamp.toEpochMilli
      metrics.defaultRegistry.timer("uploadToStartProcessing").update(interval, TimeUnit.MILLISECONDS)
    }

  private def addUploadToEndScanMetrics(uploadedTimestamp: Instant, endTimeMilliseconds: Long): Future[Unit] =
    Future.successful {
      val interval = endTimeMilliseconds - uploadedTimestamp.toEpochMilli
      metrics.defaultRegistry.timer("uploadToScanComplete").update(interval, TimeUnit.MILLISECONDS)
    }
}
