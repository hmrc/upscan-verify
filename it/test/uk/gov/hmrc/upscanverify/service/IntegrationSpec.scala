/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.upscanverify.service

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{PutObjectResult, S3Object}
import com.amazonaws.util.StringInputStream
import org.apache.commons.io.IOUtils
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceableModuleConversions}
import uk.gov.hmrc.upscanverify.connector.aws.AWSClientModule
import uk.gov.hmrc.upscanverify.test.MockAWSClientModule

import java.nio.charset.StandardCharsets.UTF_8

class IntegrationSpec
  extends AnyWordSpec
     with should.Matchers
     with MockitoSugar
     with GuiceOneServerPerSuite
     with GuiceableModuleConversions
     with BeforeAndAfterEach
     with BeforeAndAfterAll:

  override lazy val app: Application =
    GuiceApplicationBuilder()
      .disable(classOf[AWSClientModule])
      .overrides(MockAWSClientModule())
      .build()

  lazy val s3 = app.injector.instanceOf[AmazonS3]

  "IntegrationSpec" can:
    "Put and Get to an S3 bucket" in new S3TestMock:
      val putActualResult: PutObjectResult = s3.putObject("myBucket", "myKey", "myContent")

      putActualResult.getVersionId shouldBe "0.666"

      val getActualResult: S3Object = s3.getObject("myBucket", "myKey")

      contentAsString(getActualResult) shouldBe "myContent"

  trait S3TestMock:
    val putResult: PutObjectResult = PutObjectResult()
    putResult.setVersionId("0.666")

    when(s3.putObject("myBucket", "myKey", "myContent"))
      .thenReturn(putResult)

    val getResult: S3Object = S3Object()
    getResult.setBucketName("myBucket")
    getResult.setKey("myKey")
    getResult.setObjectContent(StringInputStream("myContent"))

    when(s3.getObject("myBucket", "myKey"))
      .thenReturn(getResult)

  def contentAsString(s3object: S3Object): String =
    IOUtils.toString(s3object.getObjectContent, UTF_8)
