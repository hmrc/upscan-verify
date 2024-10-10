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

import uk.gov.hmrc.upscanverify.model.S3ObjectLocation

import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.concurrent.Future

case class InboundObjectDetails(
  metadata: InboundObjectMetadata,
  clientIp: String,
  location: S3ObjectLocation
)

case class InboundObjectMetadata(
  items            : Map[String, String],
  uploadedTimestamp: Instant,
  fileSize         : Long
):
  def originalFilename: Option[String] =
    items.get("original-filename")

  def consumingService: Option[String] =
    items.get("consuming-service")

case class OutboundObjectMetadata(
  items: Map[String, String]
)

object OutboundObjectMetadata:
  private def commonMetadata(detailsOfSourceFile: InboundObjectDetails): Map[String, String] =
    detailsOfSourceFile.metadata.items
      ++ Map(
        "file-reference" -> detailsOfSourceFile.location.objectKey,
        "client-ip"      -> detailsOfSourceFile.clientIp
      )
      ++ detailsOfSourceFile.location.objectVersion.map("file-version" -> _)

  def valid(
    detailsOfSourceFile       : InboundObjectDetails,
    checksum                  : String,
    mimeType                  : MimeType,
    additionalOutboundMetadata: Map[String, String]
  ): OutboundObjectMetadata =
    OutboundObjectMetadata(
      commonMetadata(detailsOfSourceFile)
        ++ Map(
          "initiate-date" -> DateTimeFormatter.ISO_INSTANT.format(detailsOfSourceFile.metadata.uploadedTimestamp),
          "checksum"      -> checksum,
          "mime-type"     -> mimeType.value
        )
        ++ additionalOutboundMetadata
    )

  def invalid(
    detailsOfSourceFile       : InboundObjectDetails,
    additionalOutboundMetadata: Map[String, String]
  ): OutboundObjectMetadata =
    OutboundObjectMetadata(
      commonMetadata(detailsOfSourceFile)
        ++ Map(
          "initiate-date" -> DateTimeFormatter.ISO_INSTANT.format(detailsOfSourceFile.metadata.uploadedTimestamp)
        )
        ++ additionalOutboundMetadata
    )

case class ObjectContent(
  inputStream: InputStream,
  length     : Long
)

trait FileManager:
  def copyObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    metadata      : OutboundObjectMetadata
  ): Future[Unit]

  def writeObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    content       : InputStream,
    metadata      : OutboundObjectMetadata
  ): Future[Unit]

  def delete(
    objectLocation: S3ObjectLocation
  ): Future[Unit]

  def getObjectMetadata(
    objectLocation: S3ObjectLocation
  ): Future[InboundObjectMetadata]

  def withObjectContent[T](
    objectLocation: S3ObjectLocation
  )(
    function: ObjectContent => Future[T]
  ): Future[T]
