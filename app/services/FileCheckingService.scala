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

package services

import model._
import play.api.Logger
import uk.gov.hmrc.http.logging.LoggingDetails
import util.logging.WithLoggingDetails.withLoggingDetails

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class FileCheckingService @Inject()(
  fileManager: FileManager,
  virusScanningService: ScanningService,
  fileTypeCheckingService: FileTypeCheckingService)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  def check(location: S3ObjectLocation, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[FileRejected, FileValidationSuccess]] = {
    withLoggingDetails(ld) {
      logger.debug(s"Checking upload Key=[${location.objectKey}]")
    }
    virusScan(location, objectMetadata) flatMap {
      case Left(fi: FileInfected) => Future.successful(Left(FileRejected(Left(fi))))
      case Right(nvf: NoVirusFound) =>
        fileType(location, objectMetadata) map {
          case Left(ift) => Left(FileRejected(Right(nvf), Some(ift)))
          case Right(FileAllowed(mime, timings)) =>
            Right(FileValidationSuccess(nvf.checksum, mime, nvf.virusScanTimings, timings))
        }
    }
  }

  private def virusScan(location: S3ObjectLocation, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[FileInfected, NoVirusFound]] =
    fileManager.withObjectContent(location) { objectContent: ObjectContent =>
      virusScanningService.scan(location, objectContent, objectMetadata)
    }

  private def fileType(location: S3ObjectLocation, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[FileTypeError, FileAllowed]] =
    fileManager.withObjectContent(location) { objectContent: ObjectContent =>
      fileTypeCheckingService.scan(location, objectContent, objectMetadata)
    }

}
