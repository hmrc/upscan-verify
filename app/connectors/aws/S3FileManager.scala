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
import com.amazonaws.services.s3.model.{CopyObjectRequest, ObjectMetadata => S3ObjectMetadata}
import config.ServiceConfiguration
import model.S3ObjectLocation
import services.{FileManager, ObjectContent, ObjectMetadata}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class S3FileManager @Inject()(s3Client: AmazonS3, config: ServiceConfiguration)(implicit ec: ExecutionContext)
    extends FileManager {
  override def copyToOutboundBucket(file: S3ObjectLocation): Future[Unit] =
    buildOutboundObjectMetadata(file) flatMap { updatedMetadata =>
      val request = new CopyObjectRequest(file.bucket, file.objectKey, config.outboundBucket, file.objectKey)
      request.setNewObjectMetadata(updatedMetadata)
      Future(s3Client.copyObject(request))
    }

  override def writeToQuarantineBucket(
    file: S3ObjectLocation,
    content: InputStream,
    metadata: ObjectMetadata): Future[Unit] = {
    val quarantineObjectMetadata = buildQuarantineObjectMetadata(metadata)
    Future {
      s3Client.putObject(config.quarantineBucket, file.objectKey, content, quarantineObjectMetadata)
    }
  }

  private def buildQuarantineObjectMetadata(inboundObjectMetadata: ObjectMetadata) = {
    val result = new com.amazonaws.services.s3.model.ObjectMetadata()
    result.setUserMetadata(inboundObjectMetadata.items.asJava)
    result
  }

  private def buildOutboundObjectMetadata(file: S3ObjectLocation): Future[S3ObjectMetadata] =
    for {
      inboundMetadata <- Future(s3Client.getObjectMetadata(file.bucket, file.objectKey))
    } yield {
      val lastModified          = inboundMetadata.getLastModified
      val lastModifiedFormatted = DateTimeFormatter.ISO_INSTANT.format(lastModified.toInstant)
      inboundMetadata.addUserMetadata("initiate-date", lastModifiedFormatted)
      inboundMetadata
    }

  override def delete(file: S3ObjectLocation) =
    Future(
      s3Client.deleteObject(file.bucket, file.objectKey)
    )

  override def getObjectContent(file: S3ObjectLocation): Future[ObjectContent] =
    Future {
      val fileFromLocation = s3Client.getObject(file.bucket, file.objectKey)
      ObjectContent(fileFromLocation.getObjectContent, fileFromLocation.getObjectMetadata.getContentLength)
    }

  override def getObjectMetadata(file: S3ObjectLocation): Future[services.ObjectMetadata] =
    for {
      metadata <- Future(s3Client.getObjectMetadata(file.bucket, file.objectKey))
    } yield {
      ObjectMetadata(metadata.getUserMetadata.asScala.toMap)
    }
}
