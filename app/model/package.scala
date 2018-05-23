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

package model

import uk.gov.hmrc.clamav.model.{Clean, Infected, ScanningResult}

import scala.concurrent.Future

case class Message(id: String, body: String, receiptHandle: String)

case class S3ObjectLocation(bucket: String, objectKey: String)
case class FileUploadEvent(location: S3ObjectLocation)

sealed trait FileCheckingResult {
  val location: S3ObjectLocation

  def andThen(f: (S3ObjectLocation) => Future[FileCheckingResult]): Future[FileCheckingResult] =
    this match {
      case v: ValidFileCheckingResult   => f(v.location)
      case i: InvalidFileCheckingResult => Future.successful(i)
    }
}

object FileCheckingResult {
  def apply(virusResult: ScanningResult, location: S3ObjectLocation): FileCheckingResult =
    virusResult match {
      case Clean             => ValidFileCheckingResult(location)
      case Infected(message) => InvalidFileCheckingResult(location, message)
    }
}

case class ValidFileCheckingResult(location: S3ObjectLocation) extends FileCheckingResult

case class InvalidFileCheckingResult(location: S3ObjectLocation, details: String) extends FileCheckingResult
