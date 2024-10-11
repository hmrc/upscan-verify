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

package uk.gov.hmrc.upscanverify.connector.aws

import play.api.Logging
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{CopyObjectRequest, DeleteObjectRequest, GetObjectRequest, GetObjectResponse, HeadObjectRequest, MetadataDirective, PutObjectRequest}
import uk.gov.hmrc.play.http.logging.Mdc
import uk.gov.hmrc.upscanverify.model.S3ObjectLocation
import uk.gov.hmrc.upscanverify.service.{FileManager, InboundObjectMetadata, OutboundObjectMetadata}

import java.io.InputStream
import javax.inject.{Inject, Singleton}
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class S3FileManager @Inject()(s3Client: S3AsyncClient)(using ExecutionContext) extends FileManager with Logging:

  override def copyObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    metadata      : OutboundObjectMetadata
  ): Future[Unit] =
    val request =
      CopyObjectRequest
        .builder()
        .sourceBucket(sourceLocation.bucket)
        .sourceKey(sourceLocation.objectKey)
        .destinationBucket(targetLocation.bucket)
        .destinationKey(targetLocation.objectKey)
        .metadata(metadata.items.asJava)
        .metadataDirective(MetadataDirective.REPLACE)
    sourceLocation.objectVersion.foreach(request.sourceVersionId)

    val r = request.build()

    logger.debug:
      s"Copying Key=[${sourceLocation.objectKey}] with metadata: ${metadata.items}, ${r.metadataDirectiveAsString}"

    Mdc
      .preservingMdc:
        s3Client
          .copyObject(r)
          .asScala
      .map: _ =>
        logger.debug:
          s"Copied object with Key=[${sourceLocation.objectKey}] from [$sourceLocation] to [$targetLocation]."

  override def writeObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    content       : String,
    metadata      : OutboundObjectMetadata
  ): Future[Unit] =
    val request =
      PutObjectRequest
        .builder()
        .bucket(targetLocation.bucket)
        .key(targetLocation.objectKey)
        .metadata(metadata.items.asJava)
    Mdc
      .preservingMdc:
        s3Client
          .putObject(
            request.build(),
            AsyncRequestBody.fromBytes(content.getBytes)
          )
          .asScala
      .map: _ =>
        logger.debug(s"Wrote object with Key=[${sourceLocation.objectKey}] to location [$targetLocation].")

  override def delete(objectLocation: S3ObjectLocation): Future[Unit] =
    val request =
      DeleteObjectRequest
        .builder()
        .bucket(objectLocation.bucket)
        .key(objectLocation.objectKey)
    objectLocation.objectVersion.foreach(request.versionId)

    Mdc
      .preservingMdc:
        s3Client
          .deleteObject(request.build())
          .asScala
      .map: _ =>
        logger.debug(s"Deleted object with Key=[${objectLocation.objectKey}] from [$objectLocation].")

  override def withObjectContent[T](
    objectLocation: S3ObjectLocation
  )(
    function: InputStream => Future[T]
  ): Future[T] =
    for
      content  <- getS3Object(objectLocation)
      _        =  logger.debug(s"Fetched content for Key=[${objectLocation.objectKey}].")
      result   <- function(content).andThen { case _ => content.close() }
    yield result

  private def getS3Object(objectLocation: S3ObjectLocation): Future[InputStream] =
    val request =
      GetObjectRequest
        .builder()
        .bucket(objectLocation.bucket)
        .key(objectLocation.objectKey)
    objectLocation.objectVersion.foreach(request.versionId)

    Mdc
      .preservingMdc:
        s3Client
          .getObject(request.build(), AsyncResponseTransformer.toBlockingInputStream())
          .asScala
      .map: x =>
        val response: GetObjectResponse = x.response
        logger.info(s"getObject: response.contentLength = ${response.contentLength}")
        logger.info(s"getObject: response.checksumSHA256 = ${response.checksumSHA256}")
        logger.info(s"getObject: response.lastModified() = ${response.lastModified}")
        logger.info(s"getObject: response.metadata = ${response.metadata.asScala}")
        x

  override def getObjectMetadata(objectLocation: S3ObjectLocation): Future[InboundObjectMetadata] =
    // ideally we'd only request the content once, and get the metadata at the same time
    val request =
      HeadObjectRequest
        .builder()
        .bucket(objectLocation.bucket)
        .key(objectLocation.objectKey)
    objectLocation.objectVersion.foreach(request.versionId  )

    Mdc
      .preservingMdc:
        s3Client
          .headObject(request.build())
          .asScala
      .map: response =>
        InboundObjectMetadata(
          response.metadata.asScala.toMap,
          response.lastModified,
          response.contentLength
        )
