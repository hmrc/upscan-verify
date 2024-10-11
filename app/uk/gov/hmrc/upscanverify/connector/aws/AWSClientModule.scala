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

import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import play.api.{Configuration, Environment}
import play.api.inject.{Binding, Module}
import software.amazon.awssdk.services.s3.S3AsyncClient

import javax.inject.{Inject, Provider}

class AWSClientModule extends Module:

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(
      bind[AmazonSQS].toProvider[SqsClientProvider],
      //bind[AmazonS3 ].toProvider[S3ClientProvider]
      bind[S3AsyncClient].toProvider[S3ClientProvider]
    )

class SqsClientProvider @Inject()() extends Provider[AmazonSQS]:
  override def get(): AmazonSQS =
    AmazonSQSClientBuilder.defaultClient()

class S3ClientProvider @Inject()() extends Provider[S3AsyncClient]:
  override def get(): S3AsyncClient =
    S3AsyncClient.builder().build()
