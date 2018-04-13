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

import java.io.ByteArrayInputStream

import javax.inject.Inject
import model.S3ObjectLocation

import scala.concurrent.{ExecutionContext, Future}

sealed trait InstanceSafety extends Product with Serializable
case object SafeToContinue extends InstanceSafety
case object ShouldTerminate extends InstanceSafety

class ScanningResultHandler @Inject()(fileManager: FileManager, virusNotifier: VirusNotifier)(
  implicit ec: ExecutionContext) {

  def handleScanningResult(result: ScanningResult): Future[InstanceSafety] =
    result match {
      case FileIsClean(file)             => handleClean(file)
      case FileIsInfected(file, details) => handleInfected(file, details)
    }

  private def handleInfected(file: S3ObjectLocation, details: String) =
    for {
      _        <- virusNotifier.notifyFileInfected(file, details)
      metadata <- fileManager.getObjectMetadata(file)
      quarantineObjectContent = new ByteArrayInputStream(details.getBytes)
      _ <- fileManager.writeToQuarantineBucket(file, quarantineObjectContent, metadata)
      _ <- fileManager.delete(file)
    } yield ShouldTerminate

  private def handleClean(file: S3ObjectLocation) =
    for {
      _ <- fileManager.copyToOutboundBucket(file)
      _ <- fileManager.delete(file)
    } yield SafeToContinue
}
