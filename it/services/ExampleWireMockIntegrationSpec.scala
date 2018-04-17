package services

import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{PutObjectResult, S3Object}
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.util.StringInputStream
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{get, put, urlPathEqualTo}
import modules.{LocalAWSClientModule, WithWireMock}
import org.apache.commons.io.IOUtils
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.http.Status
import play.api.inject.bind
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceableModuleConversions}
import uk.gov.hmrc.play.test.UnitSpec

class ExampleWireMockIntegrationSpec extends UnitSpec
                                     with GuiceOneServerPerSuite with GuiceableModuleConversions with BeforeAndAfterEach with BeforeAndAfterAll
                                     with WithWireMock {

  override lazy val app: Application = new GuiceApplicationBuilder()
    .disable(classOf[connectors.aws.AWSClientModule])
    .overrides(new LocalAWSClientModule())
    .build()

  lazy val s3 = app.injector.instanceOf[AmazonS3]

  "ExampleWireMockIntegrationSpec" should {
    "Put and Get to an S3 bucket" in {
      new S3TestWireMockSetup {
        val putActualResult: PutObjectResult = s3.putObject("myBucket", "myKey", "myContent")

        putActualResult.getVersionId shouldBe "0.666"

        val getActualResult: S3Object = s3.getObject("myBucket", "myKey")

        getActualResult.contentAsString shouldBe "myContent"
      }
    }
  }

  trait S3TestWireMockSetup {
    val putResult: PutObjectResult = new PutObjectResult()
    putResult.setVersionId("0.666")

    WireMock.stubFor(put(urlPathEqualTo("/myBucket/myKey"))
            .willReturn(WireMock.aResponse()
            .withBody("{0.666}")
            .withStatus(Status.OK)))

    val getResult: S3Object = new S3Object()
    getResult.setBucketName("myBucket")
    getResult.setKey("myKey")
    getResult.setObjectContent(new StringInputStream("myContent"))

//    Mockito.when(s3.getObject(eql("myBucket"), eql("myKey")))
//      .thenReturn(getResult)
  }

  implicit class S3ObjectOps(s3object: S3Object) {
    def contentAsString: String = IOUtils.toString(s3object.getObjectContent)
  }
}


