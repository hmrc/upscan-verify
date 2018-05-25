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

import com.amazonaws.auth.{AWSCredentialsProvider, AWSSessionCredentials, EC2ContainerCredentialsProviderWrapper}
import config.ServiceConfiguration
import model.ConsumingServicesConfiguration
import org.scalatest.Matchers
import uk.gov.hmrc.play.test.UnitSpec

class ProviderOfAWSCredentialsSpec extends UnitSpec with Matchers {

  "ProviderOfAWSCredentials" should {
    "create BasicSessionCredentials if session token provided" in {

      val configuration = new ServiceConfiguration {

        override def accessKeyId: String = "KEY_ID"

        override def awsRegion: String = ???

        override def secretAccessKey: String = "ACCESS_KEY"

        override def sessionToken: Option[String] = Some("SESSION_TOKEN")

        override def inboundQueueUrl: String = ???

        override def retryInterval = ???

        override def outboundBucket = ???

        override def quarantineBucket = ???

        override def useContainerCredentials = false

        override def processingBatchSize: Int = ???

        override def consumingServicesConfiguration: ConsumingServicesConfiguration = ???
      }

      val credentials: AWSCredentialsProvider = new ProviderOfAWSCredentials(configuration).get()

      credentials.getCredentials.getAWSAccessKeyId                                   shouldBe "KEY_ID"
      credentials.getCredentials.getAWSSecretKey                                     shouldBe "ACCESS_KEY"
      credentials.getCredentials                                                     shouldBe a[AWSSessionCredentials]
      credentials.getCredentials.asInstanceOf[AWSSessionCredentials].getSessionToken shouldBe "SESSION_TOKEN"
    }

    "create BasicAWSCredentials in no session token provided" in {
      val configuration = new ServiceConfiguration {

        override def accessKeyId: String = "KEY_ID"

        override def awsRegion: String = ???

        override def secretAccessKey: String = "ACCESS_KEY"

        override def sessionToken: Option[String] = None

        override def inboundQueueUrl: String = ???

        override def retryInterval = ???

        override def outboundBucket = ???

        override def quarantineBucket = ???

        override def useContainerCredentials = false

        override def processingBatchSize: Int = ???

        override def consumingServicesConfiguration: ConsumingServicesConfiguration = ???
      }

      val credentials: AWSCredentialsProvider = new ProviderOfAWSCredentials(configuration).get()

      credentials.getCredentials.getAWSAccessKeyId shouldBe "KEY_ID"
      credentials.getCredentials.getAWSSecretKey   shouldBe "ACCESS_KEY"
      credentials.getCredentials shouldNot be(a[AWSSessionCredentials])
    }

    "create container credentials provided if it was chosen" in {
      val configuration = new ServiceConfiguration {

        override def accessKeyId: String = ???

        override def awsRegion: String = ???

        override def secretAccessKey: String = ???

        override def sessionToken: Option[String] = None

        override def inboundQueueUrl: String = ???

        override def retryInterval = ???

        override def outboundBucket = ???

        override def quarantineBucket = ???

        override def useContainerCredentials = true

        override def processingBatchSize: Int = ???

        override def consumingServicesConfiguration: ConsumingServicesConfiguration = ???
      }

      val credentials: AWSCredentialsProvider = new ProviderOfAWSCredentials(configuration).get()

      credentials shouldBe a[EC2ContainerCredentialsProviderWrapper]
    }
  }

}
