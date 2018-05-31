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

package connectors.aws

import java.io.InputStream
import java.time.format.DateTimeFormatter
import javax.inject.Inject

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{CopyObjectRequest, S3Object, ObjectMetadata => S3ObjectMetadata}
import config.ServiceConfiguration
import model.S3ObjectLocation
import play.api.Logger
import services.{FileManager, ObjectContent, ObjectMetadata}
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import util.logging.LoggingDetails

import scala.collection.JavaConverters._
import scala.concurrent.Future

class S3ObjectContent(override val length: Long, s3Object: S3Object) extends ObjectContent {
  override def close(): Unit = s3Object.close()

  override def inputStream: InputStream = s3Object.getObjectContent
}

class S3FileManager @Inject()(s3Client: AmazonS3, config: ServiceConfiguration) extends FileManager {

  override def copyToOutboundBucket(objectLocation: S3ObjectLocation): Future[Unit] = {
    implicit val ld = LoggingDetails.fromS3ObjectLocation(objectLocation)

    buildOutboundObjectMetadata(objectLocation) flatMap { metadata =>
      val request = new CopyObjectRequest(
        objectLocation.bucket,
        objectLocation.objectKey,
        config.outboundBucket,
        objectLocation.objectKey)
      request.setNewObjectMetadata(metadata)
      Future {
        s3Client.copyObject(request)
        Logger.debug(s"Copied object with objectKey: [${objectLocation.objectKey}], to outbound bucket.")

      }
    }
  }

  override def writeToQuarantineBucket(
    objectLocation: S3ObjectLocation,
    content: InputStream,
    metadata: ObjectMetadata): Future[Unit] = {
    implicit val ld = LoggingDetails.fromS3ObjectLocation(objectLocation)

    val quarantineObjectMetadata = buildQuarantineObjectMetadata(metadata)

    Future {
      s3Client.putObject(config.quarantineBucket, objectLocation.objectKey, content, quarantineObjectMetadata)
      Logger.debug(s"Wrote object with objectKey: [${objectLocation.objectKey}], to quarantine bucket.")
    }
  }

  private def buildQuarantineObjectMetadata(inboundObjectMetadata: ObjectMetadata): S3ObjectMetadata = {
    val quarantineMetadata = new com.amazonaws.services.s3.model.ObjectMetadata()
    quarantineMetadata.setUserMetadata(inboundObjectMetadata.items.asJava)
    quarantineMetadata
  }

  private def buildOutboundObjectMetadata(objectLocation: S3ObjectLocation): Future[S3ObjectMetadata] = {
    implicit val ld = LoggingDetails.fromS3ObjectLocation(objectLocation)

    for {
      inboundMetadata <- getObjectMetadata(objectLocation)
    } yield {
      val lastModified =
        DateTimeFormatter.ISO_INSTANT.format(inboundMetadata.uploadedTimestamp)
      val outboundMetadataItems = inboundMetadata.items + ("initiate-date" -> lastModified)
      val outboundMetadata      = new com.amazonaws.services.s3.model.ObjectMetadata()
      outboundMetadata.setUserMetadata(outboundMetadataItems.asJava)
      outboundMetadata
    }
  }

  override def delete(objectLocation: S3ObjectLocation) = {
    implicit val ld = LoggingDetails.fromS3ObjectLocation(objectLocation)

    Future {
      s3Client.deleteObject(objectLocation.bucket, objectLocation.objectKey)
    }
  }

  override def getObjectContent(objectLocation: S3ObjectLocation): Future[ObjectContent] = {
    implicit val ld = LoggingDetails.fromS3ObjectLocation(objectLocation)

    Future {
      val fileFromLocation = s3Client.getObject(objectLocation.bucket, objectLocation.objectKey)
      Logger.debug(s"Fetched content for objectKey: [${objectLocation.objectKey}].")
      new S3ObjectContent(fileFromLocation.getObjectMetadata.getContentLength, fileFromLocation)
    }
  }

  override def getObjectMetadata(objectLocation: S3ObjectLocation): Future[services.ObjectMetadata] = {
    implicit val ld = LoggingDetails.fromS3ObjectLocation(objectLocation)

    for {
      metadata <- Future(s3Client.getObjectMetadata(objectLocation.bucket, objectLocation.objectKey))
    } yield {
      Logger.debug(s"Fetched metadata for objectKey: [${objectLocation.objectKey}].")
      ObjectMetadata(metadata.getUserMetadata.asScala.toMap, metadata.getLastModified.toInstant)
    }
  }
}
