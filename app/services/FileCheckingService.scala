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

import java.time.{Clock, Instant}

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
  fileTypeCheckingService: FileTypeCheckingService,
  clock: Clock)(implicit ec: ExecutionContext) {

  def check(location: S3ObjectLocation, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[FileRejected, FileValidationSuccess]] = {

    virusScan(location, objectMetadata) flatMap {
      case Left(fi: FileInfected)                => FileRejected.futureLeft(fi)
      case Right(nvf: NoVirusFound)              =>

        fileType(location, objectMetadata) map {
          case Left(ift: IncorrectFileType)      => FileRejected.left(nvf, ift)
          case Right(FileAllowed(mime, timings)) => FileRejected.right(FileValidationSuccess(nvf.checksum,mime,nvf.virusScanTimings,timings))
        }
    }
  }

  private def virusScan(location: S3ObjectLocation, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[FileValidationFailure, NoVirusFound]] =

    fileManager.withObjectContent(location) { objectContent: ObjectContent =>
      virusScanningService.scan(location, objectContent, objectMetadata)
    }

  private def fileType(location: S3ObjectLocation, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[FileValidationFailure, FileAllowed]] =

    fileManager.withObjectContent(location) { objectContent: ObjectContent =>
      fileTypeCheckingService.scan(location, objectContent, objectMetadata)
    }

}
