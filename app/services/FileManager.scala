/*
 * Copyright 2021 HM Revenue & Customs
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

import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

import model.S3ObjectLocation
import uk.gov.hmrc.http.logging.LoggingDetails

import scala.concurrent.Future

case class InboundObjectDetails(
  metadata: InboundObjectMetadata,
  clientIp: String,
  location: S3ObjectLocation
)

case class InboundObjectMetadata(items: Map[String, String], uploadedTimestamp: Instant, fileSize: Long) {
  def originalFilename: Option[String] = items.get("original-filename")
  def consumingService: Option[String] = items.get("consuming-service")
}

sealed trait OutboundObjectMetadata {

  final def items: Map[String, String] = detailsOfSourceFile.metadata.items ++ commonMetadata ++ outcomeSpecificMetadata

  def detailsOfSourceFile: InboundObjectDetails

  private def commonMetadata: Map[String, String] =
    Map("file-reference" -> detailsOfSourceFile.location.objectKey, "client-ip" -> detailsOfSourceFile.clientIp) ++
      detailsOfSourceFile.location.objectVersion.map(value => "file-version" -> value)

  def outcomeSpecificMetadata: Map[String, String] = Map.empty
}

case class ValidOutboundObjectMetadata(detailsOfSourceFile: InboundObjectDetails,
                                       checksum: String,
                                       mimeType: MimeType,
                                       additionalOutboundMetadata: Map[String,String])
  extends OutboundObjectMetadata {

  override lazy val outcomeSpecificMetadata: Map[String, String] = {
    Map(
      "initiate-date" -> DateTimeFormatter.ISO_INSTANT.format(detailsOfSourceFile.metadata.uploadedTimestamp),
      "checksum"      -> checksum,
      "mime-type"     -> mimeType.value
    ) ++ additionalOutboundMetadata
  }

}

case class InvalidOutboundObjectMetadata(detailsOfSourceFile: InboundObjectDetails,
                                         additionalOutboundMetadata: Map[String,String]) extends OutboundObjectMetadata {
  override lazy val outcomeSpecificMetadata: Map[String, String] = {
    Map(
      "initiate-date" -> DateTimeFormatter.ISO_INSTANT.format(detailsOfSourceFile.metadata.uploadedTimestamp)
    ) ++ additionalOutboundMetadata
  }
}

case class ObjectContent(inputStream: InputStream, length: Long)

trait FileManager {
  def copyObject(sourceLocation: S3ObjectLocation, targetLocation: S3ObjectLocation, metadata: OutboundObjectMetadata)(
    implicit loggingDetails: LoggingDetails): Future[Unit]
  def writeObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    content: InputStream,
    metadata: OutboundObjectMetadata)(implicit loggingDetails: LoggingDetails): Future[Unit]
  def delete(objectLocation: S3ObjectLocation)(implicit loggingDetails: LoggingDetails): Future[Unit]
  def getObjectMetadata(objectLocation: S3ObjectLocation)(
    implicit loggingDetails: LoggingDetails): Future[InboundObjectMetadata]

  def withObjectContent[T](objectLocation: S3ObjectLocation)(function: ObjectContent => Future[T])(
    implicit loggingDetails: LoggingDetails): Future[T]
}
