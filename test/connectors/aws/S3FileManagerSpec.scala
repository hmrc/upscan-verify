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

import java.io.ByteArrayInputStream
import java.util
import java.util.{Calendar, GregorianCalendar}

import com.amazonaws.SdkClientException
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{CopyObjectRequest, CopyObjectResult, ObjectMetadata, S3Object}
import config.ServiceConfiguration
import model.S3ObjectLocation
import org.apache.commons.io.IOUtils
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{doThrow, verify, verifyNoMoreInteractions, when}
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{Assertions, GivenWhenThen, Matchers}
import uk.gov.hmrc.play.test.UnitSpec

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class S3FileManagerSpec extends UnitSpec with Matchers with Assertions with GivenWhenThen with MockitoSugar {
  private val configuration = mock[ServiceConfiguration]
  when(configuration.outboundBucket).thenReturn("outboundBucket")
  when(configuration.quarantineBucket).thenReturn("quarantineBucket")

  private val awsLastModified      = new GregorianCalendar(2018, Calendar.JANUARY, 27).getTime
  private val metadataLastModified = awsLastModified.toInstant

  "S3FileManager" should {
    "allow to copy file from inbound bucket to outbound bucket" in {
      val s3Metadata = mock[ObjectMetadata]
      when(s3Metadata.getLastModified).thenReturn(awsLastModified)

      val s3client: AmazonS3 = mock[AmazonS3]
      when(s3client.copyObject(any(): CopyObjectRequest)).thenReturn(new CopyObjectResult())
      when(s3client.getObjectMetadata(any(), any())).thenReturn(s3Metadata)

      val fileManager = new S3FileManager(s3client, configuration)

      When("copying the file is requested")
      Await.result(fileManager.copyToOutboundBucket(S3ObjectLocation("inboundBucket", "file")), 2.seconds)

      Then("the S3 object metadata should be retrieved for copying")
      verify(s3client).getObjectMetadata("inboundBucket", "file")

      And("the S3 copy method of AWS client should be called")
      verify(s3client).copyObject(any(): CopyObjectRequest)
      verifyNoMoreInteractions(s3client)
    }

    "return error if copying the file failed" in {

      val s3client: AmazonS3 = mock[AmazonS3]
      when(s3client.copyObject(any(), any(), any(), any())).thenThrow(new RuntimeException("exception"))
      val fileManager = new S3FileManager(s3client, configuration)

      When("copying the file is requested")
      val result = Await.ready(fileManager.copyToOutboundBucket(S3ObjectLocation("inboundBucket", "file")), 2.seconds)

      Then("error is returned")

      ScalaFutures.whenReady(result.failed) { error =>
        error shouldBe a[RuntimeException]
      }
    }

    "allow to delete file" in {

      val s3client: AmazonS3 = mock[AmazonS3]
      val fileManager        = new S3FileManager(s3client, configuration)

      When("deleting the file is requested")
      Await.result(fileManager.delete(S3ObjectLocation("inboundBucket", "file")), 2.seconds)

      Then("the S3 copy method of AWS client should be called")
      verify(s3client).deleteObject("inboundBucket", "file")
      verifyNoMoreInteractions(s3client)

    }

    "allow to retrieve objects metadata" in {
      val s3client: AmazonS3 = mock[AmazonS3]
      val fileManager        = new S3FileManager(s3client, configuration)

      val userMetadata = new util.HashMap[String, String]()
      userMetadata.put("callbackUrl", "http://some.callback.url")
      val fileMetadata = new ObjectMetadata()
      fileMetadata.setUserMetadata(userMetadata)
      fileMetadata.setLastModified(awsLastModified)

      when(s3client.getObjectMetadata("inboundBucket", "file")).thenReturn(fileMetadata)

      When("fetching objects metadata")
      val metadata = Await.result(fileManager.getObjectMetadata(S3ObjectLocation("inboundBucket", "file")), 2.seconds)

      Then("metadata is properly returned")
      metadata shouldBe services.ObjectMetadata(Map("callbackUrl" -> "http://some.callback.url"), metadataLastModified)
    }

    "return error if retrieving metadata fails" in {}

    "return error if deleting the file failed" in {

      val s3client: AmazonS3 = mock[AmazonS3]
      Given("deleting file would fail")
      doThrow(new RuntimeException("exception")).when(s3client).deleteObject(any(), any())
      val fileManager = new S3FileManager(s3client, configuration)

      When("deleting the file is requested")
      val result = Await.ready(fileManager.delete(S3ObjectLocation("inboundBucket", "file")), 2.seconds)

      Then("error is returned")
      ScalaFutures.whenReady(result.failed) { error =>
        error shouldBe a[RuntimeException]
      }
    }

    "return bytes of a successfully retrieved file" in {
      val fileLocation           = S3ObjectLocation("inboundBucket", "file")
      val byteArray: Array[Byte] = "Hello World".getBytes

      val s3Object = new S3Object()
      s3Object.setObjectContent(new ByteArrayInputStream(byteArray))
      val fileMetadata = new ObjectMetadata()
      fileMetadata.setContentLength(byteArray.length)
      s3Object.setObjectMetadata(fileMetadata)

      val s3client: AmazonS3 = mock[AmazonS3]
      when(s3client.getObject(fileLocation.bucket, fileLocation.objectKey)).thenReturn(s3Object)

      Given("a valid file location")
      val fileManager = new S3FileManager(s3client, configuration)

      When("the bytes are requested")
      val result = Await.result(fileManager.getObjectContent(fileLocation), 2.seconds)

      Then("expected byte array is returned")
      result.length                           shouldBe byteArray.length
      IOUtils.toByteArray(result.inputStream) shouldBe byteArray
    }

    "return error if file retrieval fails" in {
      val fileLocation = S3ObjectLocation("inboundBucket", "file")

      val s3client: AmazonS3 = mock[AmazonS3]
      Mockito
        .doThrow(new RuntimeException("exception"))
        .when(s3client)
        .getObject(fileLocation.bucket, fileLocation.objectKey)

      Given("a call to the S3 client errors")
      val fileManager = new S3FileManager(s3client, configuration)

      When("the bytes are requested")
      val result = Await.ready(fileManager.getObjectContent(fileLocation), 2.seconds)

      Then("error is returned")
      ScalaFutures.whenReady(result.failed) { error =>
        error shouldBe a[RuntimeException]
      }
    }

    "return successful if copy of file metadata and content to quarantine bucket succeeds" in {
      Given("a valid file location and details of an error")
      val fileLocation = S3ObjectLocation("inboundBucket", "file")

      val s3client: AmazonS3 = mock[AmazonS3]
      val fileManager        = new S3FileManager(s3client, configuration)

      When("a call to copy to quarantine is made")
      val content  = new ByteArrayInputStream("This is a dirty file".getBytes)
      val metadata = services.ObjectMetadata(Map("callbackUrl" -> "http://some.callback.url"), metadataLastModified)
      Await.result(fileManager.writeToRejectedBucket(fileLocation, content, metadata), 2.seconds)

      Then("a new S3 object with details set as contents and object metadata set should be created")
      verify(s3client).putObject(any(), any(), any(), any())

    }

    "return failure if put to quarantine bucket fails" in {
      Given("a valid file location and details of an error")
      val fileLocation = S3ObjectLocation("inboundBucket", "file")

      val userMetadata = new util.HashMap[String, String]()
      userMetadata.put("callbackUrl", "http://some.callback.url")
      val fileMetadata = new ObjectMetadata()
      fileMetadata.setUserMetadata(userMetadata)
      fileMetadata.setContentType("application/xml")

      val s3client: AmazonS3 = mock[AmazonS3]
      when(s3client.getObjectMetadata(any(), any())).thenReturn(fileMetadata)
      when(s3client.putObject(any(), any(), any(), any())).thenThrow(new SdkClientException("This is a put exception"))

      val fileManager = new S3FileManager(s3client, configuration)

      When("a call to copy to quarantine is made")
      val content  = new ByteArrayInputStream("This is a dirty file".getBytes)
      val metadata = services.ObjectMetadata(Map("callbackUrl" -> "http://some.callback.url"), metadataLastModified)
      val result   = Await.ready(fileManager.writeToRejectedBucket(fileLocation, content, metadata), 2.seconds)

      And("a new S3 object with details set as contents and object metadata set should be created")
      val metadataCaptor: ArgumentCaptor[ObjectMetadata] = ArgumentCaptor.forClass(classOf[ObjectMetadata])
      verify(s3client).putObject(any(), any(), any(), metadataCaptor.capture())

      And("the new object should contain metadata copied from inbound object")
      metadataCaptor.getValue.getUserMetadata.asScala shouldBe userMetadata.asScala

      And("the new object shouldn't contain any other metatada of the inbound object")
      metadataCaptor.getValue.getContentType shouldBe null

      And("only users metadata have been copied")

      And("error is returned")
      ScalaFutures.whenReady(result.failed) { error =>
        error            shouldBe a[SdkClientException]
        error.getMessage shouldBe "This is a put exception"
      }
    }
  }
}
