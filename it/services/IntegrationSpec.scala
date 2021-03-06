package services

import java.nio.charset.StandardCharsets.UTF_8

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{PutObjectResult, S3Object}
import com.amazonaws.util.StringInputStream
import modules.MockAWSClientModule
import org.apache.commons.io.IOUtils
import org.mockito.scalatest.MockitoSugar
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceableModuleConversions}

class IntegrationSpec extends AnyWordSpecLike with should.Matchers with MockitoSugar
                             with GuiceOneServerPerSuite with GuiceableModuleConversions with BeforeAndAfterEach with BeforeAndAfterAll {

  override lazy val app: Application = new GuiceApplicationBuilder()
    .disable(classOf[connectors.aws.AWSClientModule])
    .overrides(new MockAWSClientModule())
    .build()

  lazy val s3 = app.injector.instanceOf[AmazonS3]

  "IntegrationSpec" can {
    "Put and Get to an S3 bucket" in {
      new S3TestMock {
        val putActualResult: PutObjectResult = s3.putObject("myBucket", "myKey", "myContent")

        putActualResult.getVersionId shouldBe "0.666"

        val getActualResult: S3Object = s3.getObject("myBucket", "myKey")

        getActualResult.contentAsString shouldBe "myContent"
      }
    }
  }

  trait S3TestMock {
    val putResult: PutObjectResult = new PutObjectResult()
    putResult.setVersionId("0.666")

    when(s3.putObject("myBucket", "myKey", "myContent")).thenReturn(putResult)

    val getResult: S3Object = new S3Object()
    getResult.setBucketName("myBucket")
    getResult.setKey("myKey")
    getResult.setObjectContent(new StringInputStream("myContent"))

    when(s3.getObject("myBucket", "myKey")).thenReturn(getResult)
  }

  implicit class S3ObjectOps(s3object: S3Object) {
    def contentAsString: String = IOUtils.toString(s3object.getObjectContent, UTF_8)
  }
}