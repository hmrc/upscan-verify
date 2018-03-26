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

import javax.inject.Inject

import model.{S3ObjectLocation, UploadedFile}
import uk.gov.hmrc.clamav._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

sealed trait ScanningResult {
  def location: S3ObjectLocation
}

case class FileIsClean(location: S3ObjectLocation) extends ScanningResult

case class FileIsInfected(location: S3ObjectLocation, details: String) extends ScanningResult

trait ScanningService {
  def scan(notification: UploadedFile): Future[ScanningResult]
}

class ClamAvScanningService @Inject()(clamClientFactory: ClamAntiVirusFactory, fileManager: FileManager)
                                     (implicit ec: ExecutionContext) extends ScanningService {

  override def scan(notification: UploadedFile): Future[ScanningResult] = {
    for {
      fileBytes       <- fileManager.getBytes(notification.location)
      antivirusClient =  clamClientFactory.getClient()
      scanResult      <- antivirusClient.sendAndCheck(fileBytes) map {
        case Success(_)     => FileIsClean(notification.location)
        case Failure(error) => FileIsInfected(notification.location, error.getMessage)
      }
    } yield {
      scanResult
    }
  }
}
