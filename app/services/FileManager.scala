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

import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

import model.S3ObjectLocation

import scala.concurrent.Future

case class InboundObjectDetails(
  metadata: InboundObjectMetadata,
  clientIp: String,
  location: S3ObjectLocation
)

case class InboundObjectMetadata(items: Map[String, String], uploadedTimestamp: Instant) {
  def originalFilename = items.get("original-filename")
  def consumingService = items.get("consuming-service")
}

sealed trait OutboundObjectMetadata {
  final def items: Map[String, String] = commonDetails ++ customMetadata

  def customMetadata: Map[String, String] = Map.empty

  def inboundDetails: InboundObjectDetails

  private def commonDetails: Map[String, String] =
    Map("file-reference" -> inboundDetails.location.objectKey, "client-ip" -> inboundDetails.clientIp) ++
      inboundDetails.location.objectVersion.map(value => "file-version" -> value)
}

case class ValidOutboundObjectMetadata(inboundDetails: InboundObjectDetails, checksum: String, mimeType: MimeType)
    extends OutboundObjectMetadata {

  override lazy val customMetadata = {
    val lastModified = DateTimeFormatter.ISO_INSTANT.format(inboundDetails.metadata.uploadedTimestamp)
    inboundDetails.metadata.items +
      ("initiate-date" -> lastModified) +
      ("checksum"      -> checksum) +
      ("mime-type"     -> mimeType.value)
  }

}

case class InvalidOutboundObjectMetadata(inboundDetails: InboundObjectDetails) extends OutboundObjectMetadata

case class ObjectContent(inputStream: InputStream, length: Long)

trait FileManager {
  def copyObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    metadata: OutboundObjectMetadata): Future[Unit]
  def writeObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    content: InputStream,
    metadata: OutboundObjectMetadata): Future[Unit]
  def delete(objectLocation: S3ObjectLocation): Future[Unit]
  def getObjectMetadata(objectLocation: S3ObjectLocation): Future[InboundObjectMetadata]

  def withObjectContent[T](objectLocation: S3ObjectLocation)(function: ObjectContent => Future[T]): Future[T]
}
