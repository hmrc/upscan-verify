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

package connectors.aws

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{CopyObjectRequest, GetObjectMetadataRequest, GetObjectRequest, S3Object, ObjectMetadata => S3ObjectMetadata}
import model.S3ObjectLocation
import play.api.Logging
import services.{FileManager, InboundObjectMetadata, ObjectContent, OutboundObjectMetadata}
import uk.gov.hmrc.http.logging.LoggingDetails
import util.logging.WithLoggingDetails.withLoggingDetails

import java.io.InputStream
import javax.inject.Inject
import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContext, Future}

class S3FileManager @Inject()(s3Client: AmazonS3)(using ExecutionContext) extends FileManager with Logging:

  override def copyObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    metadata      : OutboundObjectMetadata
  )(using loggingDetails: LoggingDetails): Future[Unit] =

    val outboundMetadata = buildS3objectMetadata(metadata)

    val request = CopyObjectRequest(
      sourceLocation.bucket,
      sourceLocation.objectKey,
      targetLocation.bucket,
      targetLocation.objectKey
    )

    request.setNewObjectMetadata(outboundMetadata)

    sourceLocation.objectVersion.foreach(request.setSourceVersionId)

    Future:
      s3Client.copyObject(request)
      withLoggingDetails(loggingDetails):
        logger.debug(
          s"Copied object with Key=[${sourceLocation.objectKey}] from [$sourceLocation] to [$targetLocation]."
        )

  override def writeObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    content       : InputStream,
    metadata      : OutboundObjectMetadata
  )(using
    loggingDetails: LoggingDetails
  ): Future[Unit] =
    val quarantineObjectMetadata = buildS3objectMetadata(metadata)

    Future:
      s3Client.putObject(targetLocation.bucket, targetLocation.objectKey, content, quarantineObjectMetadata)
      withLoggingDetails(loggingDetails):
        logger.debug(s"Wrote object with Key=[${sourceLocation.objectKey}] to location [$targetLocation].")

  private def buildS3objectMetadata(metadata: OutboundObjectMetadata): S3ObjectMetadata =
    val awsMetadata = com.amazonaws.services.s3.model.ObjectMetadata()
    awsMetadata.setUserMetadata(metadata.items.asJava)
    awsMetadata

  override def delete(objectLocation: S3ObjectLocation)(using loggingDetails: LoggingDetails): Future[Unit] =
    Future:
      objectLocation.objectVersion match
        case Some(versionId) => s3Client.deleteVersion(objectLocation.bucket, objectLocation.objectKey, versionId)
        case None            => s3Client.deleteObject(objectLocation.bucket, objectLocation.objectKey)
      withLoggingDetails(loggingDetails):
        logger.debug(s"Deleted object with Key=[${objectLocation.objectKey}] from [$objectLocation].")

  override def withObjectContent[T](
    objectLocation: S3ObjectLocation
  )(
    function: ObjectContent => Future[T]
  )(using
    loggingDetails: LoggingDetails
  ): Future[T] =
    for
      s3Object <- getS3Object(objectLocation)
      content  =  ObjectContent(s3Object.getObjectContent, s3Object.getObjectMetadata.getContentLength)
      _        =  withLoggingDetails(loggingDetails):
                    logger.debug(s"Fetched content for Key=[${objectLocation.objectKey}].")
      result   <- function(content).andThen { case _ => s3Object.close() }
    yield result

  private def getS3Object(objectLocation: S3ObjectLocation): Future[S3Object] =
    Future:
      val getObjectRequest = GetObjectRequest(objectLocation.bucket, objectLocation.objectKey)
      objectLocation.objectVersion.foreach(getObjectRequest.setVersionId)
      s3Client.getObject(getObjectRequest)

  override def getObjectMetadata(
    objectLocation: S3ObjectLocation
  )(using
    loggingDetails: LoggingDetails
  ): Future[services.InboundObjectMetadata] =

    val getMetadataRequest = GetObjectMetadataRequest(objectLocation.bucket, objectLocation.objectKey)
    objectLocation.objectVersion.foreach(getMetadataRequest.setVersionId)

    for
      metadata <- Future(s3Client.getObjectMetadata(getMetadataRequest))
    yield
      withLoggingDetails(loggingDetails):
        logger.debug(s"Fetched metadata for Key=[${objectLocation.objectKey}].")
      InboundObjectMetadata(
        metadata.getUserMetadata.asScala.toMap,
        metadata.getLastModified.toInstant,
        metadata.getContentLength
      )
