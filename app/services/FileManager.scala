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

case class InboundObjectMetadata(items: Map[String, String], uploadedTimestamp: Instant) {
  def originalFilename = items.get("original-filename")
  def consumingService = items.get("consuming-service")
}

sealed trait OutboundObjectMetadata {
  def items: Map[String, String]
}

case class ValidOutboundObjectMetadata(inboundMetadata: InboundObjectMetadata, checksum: String)
    extends OutboundObjectMetadata {

  lazy val items = {
    val lastModified = DateTimeFormatter.ISO_INSTANT.format(inboundMetadata.uploadedTimestamp)
    inboundMetadata.items +
      ("initiate-date" -> lastModified) +
      ("checksum"      -> checksum)
  }

}

case class InvalidOutboundObjectMetadata(inboundMetadata: InboundObjectMetadata) extends OutboundObjectMetadata {
  lazy val items = inboundMetadata.items
}

trait ObjectContent {
  def inputStream: InputStream
  def length: Long

  def close(): Unit
}

trait FileManager {
  def copyToOutboundBucket(objectLocation: S3ObjectLocation, metadata: OutboundObjectMetadata): Future[Unit]
  def writeToQuarantineBucket(
    objectLocation: S3ObjectLocation,
    content: InputStream,
    metadata: OutboundObjectMetadata): Future[Unit]
  def delete(objectLocation: S3ObjectLocation): Future[Unit]
  def getObjectContent(objectLocation: S3ObjectLocation): Future[ObjectContent]
  def getObjectMetadata(objectLocation: S3ObjectLocation): Future[InboundObjectMetadata]
}
