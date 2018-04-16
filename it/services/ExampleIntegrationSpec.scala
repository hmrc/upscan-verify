package services

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{PutObjectResult, S3Object, S3ObjectInputStream}
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.util.StringInputStream
import connectors.aws.{Ec2ClientProvider, ProviderOfAWSCredentials, S3ClientProvider, SqsClientProvider}
import modules.{LocalAWSClientModule, MockAWSClientModule}
import org.apache.commons.io.IOUtils
import org.mockito.ArgumentMatchers.{eq => eql}
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceableModuleConversions}
import uk.gov.hmrc.play.test.UnitSpec

class ExampleIntegrationSpec extends UnitSpec
                             with GuiceOneServerPerSuite with GuiceableModuleConversions with BeforeAndAfterEach with BeforeAndAfterAll {

  override lazy val app: Application = new GuiceApplicationBuilder()
    .disable(classOf[connectors.aws.AWSClientModule])
    .overrides(new MockAWSClientModule())
//    .overrides(
//      bind[AmazonSQS].toProvider[MockAmazonSQSProvider],
//      bind[AmazonS3].toProvider[MockAmazonS3Provider],
//      bind[AmazonEC2].toProvider[MockAmazonEC2Provider]
//    )
    .build()

  lazy val s3 = app.injector.instanceOf[AmazonS3]

  "ExampleIntegrationSpec" should {
    "Put and Get to an S3 bucket" in {
      new S3TestMockup {
        val putActualResult: PutObjectResult = s3.putObject("myBucket", "myKey", "myContent")

        putActualResult.getVersionId shouldBe "0.666"

        val getActualResult: S3Object = s3.getObject("myBucket", "myKey")

        getActualResult.contentAsString shouldBe "myContent"
      }
    }
  }

  trait S3TestMockup {
    val putResult: PutObjectResult = new PutObjectResult()
    putResult.setVersionId("0.666")

    Mockito.when(s3.putObject(eql("myBucket"), eql("myKey"), eql("myContent")))
           .thenReturn(putResult)

    val getResult: S3Object = new S3Object()
    getResult.setBucketName("myBucket")
    getResult.setKey("myKey")
    getResult.setObjectContent(new StringInputStream("myContent"))

    Mockito.when(s3.getObject(eql("myBucket"), eql("myKey")))
           .thenReturn(getResult)
  }

  implicit class S3ObjectOps(s3object: S3Object) {
    def contentAsString: String = IOUtils.toString(s3object.getObjectContent)
  }
}


