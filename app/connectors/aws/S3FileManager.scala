/*
 * Copyright 2019 HM Revenue & Customs
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
import java.time.{Clock, Instant}
import java.util.UUID

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{CopyObjectRequest, GetObjectMetadataRequest, GetObjectRequest, ObjectMetadata => S3ObjectMetadata}
import javax.inject.Inject
import model.S3ObjectLocation
import play.api.Logger
import services.{FileManager, InboundObjectMetadata, ObjectContent, OutboundObjectMetadata}
import uk.gov.hmrc.http.logging.LoggingDetails
import util.logging.WithLoggingDetails.withLoggingDetails

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class S3FileManager @Inject()(s3Client: AmazonS3, clock: Clock)(implicit ec: ExecutionContext) extends FileManager {

  override def copyObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    metadata: OutboundObjectMetadata)(implicit loggingDetails: LoggingDetails): Future[Unit] = {

    val outboundMetadata = buildS3objectMetadata(metadata)
    val newKey           = UUID.randomUUID().toString
    val request = new CopyObjectRequest(
      sourceLocation.bucket,
      sourceLocation.objectKey,
      targetLocation.bucket,
      targetLocation.objectKey)
    request.setNewObjectMetadata(outboundMetadata)
    sourceLocation.objectVersion.foreach(request.setSourceVersionId)
    Future {
      s3Client.copyObject(request)
      withLoggingDetails(loggingDetails) {
        Logger.debug(s"Copied object with [$sourceLocation] to [$newKey].")
      }
    }

  }

  override def writeObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    content: InputStream,
    metadata: OutboundObjectMetadata)(implicit loggingDetails: LoggingDetails): Future[Unit] = {

    val quarantineObjectMetadata = buildS3objectMetadata(metadata)

    Future {
      s3Client.putObject(targetLocation.bucket, targetLocation.objectKey, content, quarantineObjectMetadata)
      withLoggingDetails(loggingDetails) {
        Logger.debug(s"Wrote object with objectKey: [${targetLocation.objectKey}], to quarantine bucket.")
      }
    }
  }

  private def buildS3objectMetadata(metadata: OutboundObjectMetadata): S3ObjectMetadata = {
    val awsMetadata = new com.amazonaws.services.s3.model.ObjectMetadata()
    awsMetadata.setUserMetadata(metadata.items.asJava)
    awsMetadata
  }

  override def delete(objectLocation: S3ObjectLocation)(implicit loggingDetails: LoggingDetails) =
    Future {
      objectLocation.objectVersion match {
        case Some(versionId) => s3Client.deleteVersion(objectLocation.bucket, objectLocation.objectKey, versionId)
        case None            => s3Client.deleteObject(objectLocation.bucket, objectLocation.objectKey)
      }
    }

  override def withObjectContent[T](objectLocation: S3ObjectLocation)(function: ObjectContent => Future[T])(
    implicit loggingDetails: LoggingDetails): Future[T] =
    Future {
      val getObjectRequest = new GetObjectRequest(objectLocation.bucket, objectLocation.objectKey)
      objectLocation.objectVersion.foreach(getObjectRequest.setVersionId)
      val fileFromLocation = s3Client.getObject(getObjectRequest)
      val content =
        ObjectContent(fileFromLocation.getObjectContent, fileFromLocation.getObjectMetadata.getContentLength)
      withLoggingDetails(loggingDetails) {
        Logger.debug(s"Fetched content for objectKey: [${objectLocation.objectKey}].")
      }

      val result: Future[T] = function(content)
      result.onComplete(_ => fileFromLocation.close())
      result
    }.flatMap(f => f)

  override def getObjectMetadata(objectLocation: S3ObjectLocation)(
    implicit loggingDetails: LoggingDetails): Future[services.InboundObjectMetadata] = {

    val getMetadataRequest = new GetObjectMetadataRequest(objectLocation.bucket, objectLocation.objectKey)
    objectLocation.objectVersion.foreach(getMetadataRequest.setVersionId)

    for {
      metadata <- Future(s3Client.getObjectMetadata(getMetadataRequest))
    } yield {
      withLoggingDetails(loggingDetails) {
        Logger.debug(s"Fetched metadata for objectKey: [${objectLocation.objectKey}].")
      }
      InboundObjectMetadata(metadata.getUserMetadata.asScala.toMap, metadata.getLastModified.toInstant, metadata.getContentLength)
    }
  }
}
