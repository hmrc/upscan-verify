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

package uk.gov.hmrc.upscanverify.test

import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{ReceiveMessageRequest, ReceiveMessageResult}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.{Configuration, Environment}
import play.api.inject.{Binding, Module}

import java.util.Collections.emptyList
import javax.inject.Provider
import scala.reflect.ClassTag

class MockAWSClientModule extends Module:

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(
      bind[AmazonSQS].to(EmptyMessageAmazonSQSProvider()),
      bind[AmazonS3 ].to(MockProvider[AmazonS3]()),
      bind[AmazonEC2].to(MockProvider[AmazonEC2]())
    )

/*
 * Avoid NullPointerException from SQS on integration test start as ContinuousPoller attempts to receive messages.
 */
private class EmptyMessageAmazonSQSProvider extends Provider[AmazonSQS]:
  override def get(): AmazonSQS =
    val emptyReceiveMessageResult = makeEmptyReceiveMessageResult
    val amazonSQS = mock[AmazonSQS]
    when(amazonSQS.receiveMessage(any[ReceiveMessageRequest]))
      .thenReturn(emptyReceiveMessageResult)
    amazonSQS

  private def makeEmptyReceiveMessageResult: ReceiveMessageResult =
    val result = ReceiveMessageResult()
    result.setMessages(emptyList())
    result

private class MockProvider[T <: AnyRef](using ClassTag[T]) extends Provider[T]:
  override def get(): T = mock[T]
