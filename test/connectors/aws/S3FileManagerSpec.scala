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

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import util.logging.LoggingDetails

import java.util
import java.util.{Calendar, GregorianCalendar}
import com.amazonaws.SdkClientException
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model._
import model.S3ObjectLocation
import org.apache.commons.io.IOUtils
import org.mockito.captor.ArgCaptor
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{Assertions, GivenWhenThen}
import services._
import test.UnitSpec
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._

class S3FileManagerSpec
    extends UnitSpec
    with Assertions
    with GivenWhenThen
    with Eventually {

  private val awsLastModified      = new GregorianCalendar(2018, Calendar.JANUARY, 27).getTime
  private val metadataLastModified = awsLastModified.toInstant

  implicit val ld: HeaderCarrier = LoggingDetails.fromMessageContext(MessageContext("TEST"))

  "S3FileManager" should {
    "allow to copy file from inbound bucket to outbound bucket" in {
      val s3client: AmazonS3 = mock[AmazonS3]
      when(s3client.copyObject(any[CopyObjectRequest])).thenReturn(new CopyObjectResult())

      val fileManager      = new S3FileManager(s3client)
      val inboundLocation  = S3ObjectLocation("inboundBucket", "file", Some("version"))
      val outboundLocation = S3ObjectLocation("outboundBucket", "outboundLocation", None)

      val inboundDetails = InboundObjectDetails(
        InboundObjectMetadata(items = Map.empty, uploadedTimestamp = Instant.now(), fileSize = 0),
        "127.0.0.1",
        inboundLocation
      )

      val metadata = ValidOutboundObjectMetadata(inboundDetails, "checksum", MimeType("application/xml"), Map.empty)

      When("copying the file is requested")
      Await.result(
        fileManager
          .copyObject(inboundLocation, outboundLocation, metadata),
        2.seconds)

      Then("the S3 copy method of AWS client should be called")
      val argumentCaptor = ArgCaptor[CopyObjectRequest]
      verify(s3client).copyObject(argumentCaptor)
      verifyNoMoreInteractions(s3client)

      And("proper version of the file has been downloaded")
      val request = argumentCaptor.value

      request.getSourceBucketName      shouldBe inboundLocation.bucket
      request.getSourceKey             shouldBe inboundLocation.objectKey
      request.getSourceVersionId       shouldBe inboundLocation.objectVersion.get
      request.getDestinationBucketName shouldBe outboundLocation.bucket
      request.getDestinationKey        shouldBe outboundLocation.objectKey
    }

    "return error if copying the file failed" in {

      val inboundLocation  = S3ObjectLocation("inboundBucket", "file", Some("version"))
      val outboundLocation = S3ObjectLocation("outboundBucket", "outboundLocation", None)

      val inboundDetails = InboundObjectDetails(
        InboundObjectMetadata(items = Map.empty, uploadedTimestamp = Instant.now(), fileSize = 0),
        "127.0.0.1",
        inboundLocation
      )

      val s3client = mock[AmazonS3]
      when(s3client.copyObject(any[CopyObjectRequest])).thenThrow(new RuntimeException("exception"))
      val fileManager = new S3FileManager(s3client)

      When("copying the file is requested")
      val result = Await.ready(
        fileManager
          .copyObject(
            inboundLocation,
            outboundLocation,
            ValidOutboundObjectMetadata(inboundDetails, "CHECKSUM", MimeType("application/xml"), Map.empty)
          ),
        2.seconds
      )

      Then("error is returned")

      ScalaFutures.whenReady(result.failed) { error =>
        error shouldBe a[RuntimeException]
      }
    }

    "allow to delete non versioned file" in {

      val s3client: AmazonS3 = mock[AmazonS3]
      val fileManager        = new S3FileManager(s3client)

      When("deleting the file is requested")
      Await.result(fileManager.delete(S3ObjectLocation("inboundBucket", "file", None)), 2.seconds)

      Then("the S3 copy method of AWS client should be called")
      verify(s3client).deleteObject("inboundBucket", "file")
      verifyNoMoreInteractions(s3client)

    }

    "allow to delete versioned file" in {

      val s3client: AmazonS3 = mock[AmazonS3]
      val fileManager        = new S3FileManager(s3client)

      When("deleting the file is requested")
      Await.result(fileManager.delete(S3ObjectLocation("inboundBucket", "file", Some("version"))), 2.seconds)

      Then("the S3 copy method of AWS client should be called")
      verify(s3client).deleteVersion("inboundBucket", "file", "version")
      verifyNoMoreInteractions(s3client)

    }

    "allow to retrieve objects metadata" in {
      val s3client: AmazonS3 = mock[AmazonS3]
      val fileManager        = new S3FileManager(s3client)

      val userMetadata = new util.HashMap[String, String]()
      userMetadata.put("callbackUrl", "http://some.callback.url")
      val fileMetadata = new ObjectMetadata()
      fileMetadata.setUserMetadata(userMetadata)
      fileMetadata.setLastModified(awsLastModified)

      when(s3client.getObjectMetadata(any[GetObjectMetadataRequest])).thenReturn(fileMetadata)

      When("fetching objects metadata")
      val metadata =
        Await
          .result(fileManager.getObjectMetadata(S3ObjectLocation("inboundBucket", "file", Some("version"))), 2.seconds)

      Then("metadata is properly returned")
      metadata shouldBe services
        .InboundObjectMetadata(Map("callbackUrl" -> "http://some.callback.url"), metadataLastModified, fileSize = 0)

      And("proper version of the file has been downloaded")
      val argumentCaptor = ArgCaptor[GetObjectMetadataRequest]
      verify(s3client).getObjectMetadata(argumentCaptor)
      val getObjectMetadataRequest = argumentCaptor.value

      getObjectMetadataRequest.getBucketName shouldBe "inboundBucket"
      getObjectMetadataRequest.getKey        shouldBe "file"
      getObjectMetadataRequest.getVersionId  shouldBe "version"
    }

    "return error if deleting the file failed" in {

      val s3client: AmazonS3 = mock[AmazonS3]
      Given("deleting file would fail")
      doThrow(new RuntimeException("exception")).when(s3client).deleteObject(any[String], any[String])
      val fileManager = new S3FileManager(s3client)

      When("deleting the file is requested")
      val result = Await.ready(fileManager.delete(S3ObjectLocation("inboundBucket", "file", None)), 2.seconds)

      Then("error is returned")
      ScalaFutures.whenReady(result.failed) { error =>
        error shouldBe a[RuntimeException]
      }
    }

    "return bytes of a successfully retrieved file" in {
      val fileLocation           = S3ObjectLocation("inboundBucket", "file", Some("version"))
      val fileContent            = "Hello World"
      val byteArray: Array[Byte] = fileContent.getBytes
      var s3ObjectClosed         = false

      val s3Object = new S3Object() {
        override def close(): Unit = {
          super.close()
          s3ObjectClosed = true
        }
      }
      val fileInputStream = new ByteArrayInputStream(byteArray)
      s3Object.setObjectContent(fileInputStream)

      val fileMetadata = new ObjectMetadata()
      fileMetadata.setContentLength(byteArray.length)
      s3Object.setObjectMetadata(fileMetadata)

      val s3client: AmazonS3 = mock[AmazonS3]
      when(s3client.getObject(any[GetObjectRequest])).thenReturn(s3Object)

      Given("a valid file location")
      val fileManager = new S3FileManager(s3client)

      When("the bytes are requested")
      def readingFunction(f: ObjectContent): Future[(String, Long)] =
        Future.successful((IOUtils.toString(f.inputStream, UTF_8), f.length))
      val result = Await.result(fileManager.withObjectContent(fileLocation)(readingFunction), 2.seconds)

      Then("expected byte array is returned")
      result shouldBe ((fileContent, fileContent.length))

      And("stream has been fully read and closed")
      eventually {
        fileInputStream.read() shouldBe -1
        s3ObjectClosed shouldBe true
      }

      And("proper version of the file has been downloaded")
      val argumentCaptor = ArgCaptor[GetObjectRequest]
      verify(s3client).getObject(argumentCaptor)
      val getObjectRequest = argumentCaptor.value

      getObjectRequest.getBucketName shouldBe "inboundBucket"
      getObjectRequest.getKey        shouldBe "file"
      getObjectRequest.getVersionId  shouldBe "version"
    }

    "close the object if file processing failed" in {
      val fileLocation           = S3ObjectLocation("inboundBucket", "file", None)
      val fileContent            = "Hello World"
      val byteArray: Array[Byte] = fileContent.getBytes
      var s3ObjectClosed         = false

      val s3Object = new S3Object() {
        override def close(): Unit = {
          super.close()
          s3ObjectClosed = true
        }
      }
      s3Object.setObjectContent(new ByteArrayInputStream(byteArray))
      val fileMetadata = new ObjectMetadata()
      fileMetadata.setContentLength(byteArray.length)
      s3Object.setObjectMetadata(fileMetadata)

      val s3client: AmazonS3 = mock[AmazonS3]
      when(s3client.getObject(any[GetObjectRequest])).thenReturn(s3Object)

      Given("a valid file location")
      val fileManager = new S3FileManager(s3client)

      When("the file has been read")
      Await.ready(
        fileManager.withObjectContent(fileLocation)(_ => Future.failed(new RuntimeException("expected failure"))),
        2.seconds)

      And("stream has been closed")
      eventually {
        s3ObjectClosed shouldBe true
      }
    }

    "return error if file retrieval fails" in {
      val fileLocation = S3ObjectLocation("inboundBucket", "file", None)
      val expectedGetObjectRequest = new GetObjectRequest(fileLocation.bucket, fileLocation.objectKey)

      val s3client: AmazonS3 = mock[AmazonS3]
      when(s3client.getObject(eqTo(expectedGetObjectRequest))).thenThrow(new RuntimeException("exception"))

      Given("a call to the S3 client errors")
      val fileManager = new S3FileManager(s3client)

      When("the bytes are requested")
      val result = Await.ready(fileManager.withObjectContent(fileLocation)(Future.successful), 2.seconds)

      Then("error is returned")
      ScalaFutures.whenReady(result.failed) { error =>
        error shouldBe a[RuntimeException]
      }
    }

    "return successful if copy of file metadata and content to quarantine bucket succeeds" in {
      Given("a valid file location and details of an error")
      val inboundLocation  = S3ObjectLocation("inboundBucket", "file", Some("version"))
      val outboundLocation = S3ObjectLocation("outboundBucket", "outboundLocation", None)

      val inboundDetails = InboundObjectDetails(
        InboundObjectMetadata(
          items             = Map("callbackUrl" -> "http://some.callback.url"),
          uploadedTimestamp = Instant.now(),
          fileSize          = 0
        ),
        "127.0.0.1",
        inboundLocation
      )

      val s3client: AmazonS3 = mock[AmazonS3]
      val fileManager        = new S3FileManager(s3client)

      When("a call to copy to quarantine is made")
      val content = new ByteArrayInputStream("This is a dirty file".getBytes)

      val metadata = ValidOutboundObjectMetadata(inboundDetails, "checksum", MimeType("application/xml"), Map.empty)

      Await.result(fileManager.writeObject(inboundLocation, outboundLocation, content, metadata), 2.seconds)

      Then("a new S3 object with details set as contents and object metadata set should be created")
      verify(s3client).putObject(any[String], any[String], any[InputStream], any[ObjectMetadata])

    }

    "return failure if put to quarantine bucket fails" in {

      Given("a valid file location and details of an error")
      val inboundLocation  = S3ObjectLocation("inboundBucket", "file", Some("version"))
      val outboundLocation = S3ObjectLocation("outboundBucket", "outboundLocation", None)
      val inboundDetails = InboundObjectDetails(
        InboundObjectMetadata(
          items             = Map("callbackUrl" -> "http://some.callback.url"),
          uploadedTimestamp = Instant.now(),
          fileSize          = 0
        ),
        "127.0.0.1",
        inboundLocation
      )

      val s3client: AmazonS3 = mock[AmazonS3]
      when(s3client.putObject(any[String], any[String], any[InputStream], any[ObjectMetadata])).thenThrow(
        new SdkClientException("This is a put exception"))

      val fileManager = new S3FileManager(s3client)

      When("a call to copy to quarantine is made")
      val content = new ByteArrayInputStream("This is a dirty file".getBytes)

      val metadata = ValidOutboundObjectMetadata(
        inboundDetails,
        "checksum",
        MimeType("application/xml"),
        Map.empty
      )

      val result = Await.ready(fileManager.writeObject(inboundLocation, outboundLocation, content, metadata), 2.seconds)

      And("a new S3 object with details set as contents and object metadata set should be created")
      val metadataCaptor = ArgCaptor[ObjectMetadata]
      verify(s3client).putObject(any[String], any[String], any[InputStream], metadataCaptor)

      And("the new object should contain metadata copied from inbound object")
      metadataCaptor.value.getUserMetadata.asScala shouldBe metadata.items

      And("the new object shouldn't contain any other metadata of the inbound object")
      metadataCaptor.value.getContentType shouldBe null

      And("only users metadata have been copied")

      And("error is returned")
      ScalaFutures.whenReady(result.failed) { error =>
        error            shouldBe a[SdkClientException]
        error.getMessage shouldBe "This is a put exception"
      }
    }
  }
}
