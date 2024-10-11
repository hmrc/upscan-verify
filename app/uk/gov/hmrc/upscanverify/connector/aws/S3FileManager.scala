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
import software.amazon.awssdk.services.s3.model.{CopyObjectRequest, DeleteObjectRequest, GetObjectAttributesRequest, GetObjectAttributesResponse, GetObjectRequest, GetObjectResponse, GetObjectTaggingRequest, PutObjectRequest}
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
    sourceLocation.objectVersion.foreach(request.sourceVersionId)

    s3Client
      .copyObject(request.build())
      .asScala
      .map: _ =>
        logger.debug:
          s"Copied object with Key=[${sourceLocation.objectKey}] from [$sourceLocation] to [$targetLocation]."

  // TODO writeObject is only ever used for error messages
  // we don't require to write an inputstream (then avoids contentLength and executorService requirement)
  override def writeObject(
    sourceLocation: S3ObjectLocation,
    targetLocation: S3ObjectLocation,
    content       : InputStream,
    contentLength : Long,
    metadata      : OutboundObjectMetadata
  ): Future[Unit] =
    val request =
      PutObjectRequest
        .builder()
        .bucket(targetLocation.bucket)
        .key(targetLocation.objectKey)
        .metadata(metadata.items.asJava)
    // TODO
    import java.util.concurrent.{ExecutorService, Executors}
    val e: ExecutorService = Executors.newFixedThreadPool(2) // possible create one from ExecutionContext? Move config to application.conf. Shutdown when finished
    s3Client
      .putObject(
        request.build(),
        AsyncRequestBody.fromInputStream(content, contentLength, e)
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
    for
      //tags       <- getObjectTags(objectLocation) // not authorized to perform: s3:GetObjectVersionTagging
      attributes <- getObjectAttributes(objectLocation)
    yield
      logger.debug(s"Fetched metadata for Key=[${objectLocation.objectKey}].")
      //logger.info(s"getObjectMetadata: tags = $tags")
      logger.info(s"getObjectMetadata: attributes = $attributes")
      logger.info(s"getObjectMetadata: attributes.lastModified = ${attributes.lastModified}")
      logger.info(s"getObjectMetadata: attributes.objectSize = ${attributes.objectSize}")
      logger.info(s"getObjectMetadata: attributes.checksum = ${attributes.checksum}")
      logger.info(s"getObjectMetadata: attributes.objectParts.hasParts = ${attributes.objectParts.hasParts}")
      logger.info(s"getObjectMetadata: attributes.objectParts.totalPartsCount = ${attributes.objectParts.totalPartsCount}")
      attributes.objectParts.parts.asScala.foreach: part =>
        logger.info(s"getObjectMetadata: part.partNumber = ${part.partNumber}")
        logger.info(s"getObjectMetadata: part.checksumSHA256 = ${part.checksumSHA256}")
        logger.info(s"getObjectMetadata: part.size = ${part.size}")
      InboundObjectMetadata(
        Map.empty, // TODO was metadata.getUserMetadata.asScala.toMap, help? Should we use s3Client.getObjectTagging?
        attributes.lastModified,
        attributes.objectSize // TODO same as getContentLength or need to check the parts?
      )

  private def getObjectAttributes(objectLocation: S3ObjectLocation): Future[GetObjectAttributesResponse] =
    val request =
      GetObjectAttributesRequest
        .builder()
        .bucket(objectLocation.bucket)
        .key(objectLocation.objectKey)
    objectLocation.objectVersion.foreach(request.versionId)

    s3Client
      .getObjectAttributes(request.build())
      .asScala

  private def getObjectTags(objectLocation: S3ObjectLocation): Future[Map[String, String]] =
    val request =
      GetObjectTaggingRequest
        .builder()
        .bucket(objectLocation.bucket)
        .key(objectLocation.objectKey)
    objectLocation.objectVersion.foreach(request.versionId)

    s3Client
      .getObjectTagging(request.build())
      .asScala
      .map: metadata =>
        metadata.tagSet.asScala.map(t => t.key -> t.value).toMap
