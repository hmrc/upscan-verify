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

import cats._
import cats.implicits._
import cats.data.EitherT
import javax.inject.Inject
import model._
import uk.gov.hmrc.http.logging.LoggingDetails

import scala.concurrent.{ExecutionContext, Future}

class FileCheckingService @Inject()(
  fileManager: FileManager,
  virusScanningService: ScanningService,
  fileTypeCheckingService: FileTypeCheckingService)(implicit ec: ExecutionContext) {

  def check(location: S3ObjectLocation, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[FileValidationFailure, FileValidationSuccess]] =
    (for {
      virusScanningResult      <- EitherT(scanTheFile(location, objectMetadata))
      fileTypeValidationResult <- EitherT(validateFileType(location, objectMetadata))
    } yield {
      FileValidationSuccess(virusScanningResult.checksum, fileTypeValidationResult.mimeType)
    }).value

  private def scanTheFile(location: S3ObjectLocation, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails) =
    fileManager.withObjectContent(location) { objectContent: ObjectContent =>
      virusScanningService.scan(location, objectContent, objectMetadata)
    }

  private def validateFileType(location: S3ObjectLocation, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails) =
    fileManager.withObjectContent(location) { objectContent: ObjectContent =>
      fileTypeCheckingService.scan(location, objectContent, objectMetadata)
    }

}
