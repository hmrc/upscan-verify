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

import org.apache.pekko.actor.ActorSystem
import play.api.{Configuration, Environment}
import play.api.inject.{Binding, Module}
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.s3.S3AsyncClient

import javax.inject.{Inject, Provider}

class AWSClientModule extends Module:

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(
      bind[SqsAsyncClient].toProvider[SqsClientProvider],
      bind[S3AsyncClient ].toProvider[S3ClientProvider]
    )

class SqsClientProvider @Inject()(actorSystem: ActorSystem) extends Provider[SqsAsyncClient]:
  override def get(): SqsAsyncClient =
    val client = SqsAsyncClient.builder().build()
    actorSystem.registerOnTermination(client.close())
    client

class S3ClientProvider @Inject()(actorSystem: ActorSystem) extends Provider[S3AsyncClient]:
  override def get(): S3AsyncClient =
    val client = S3AsyncClient.builder().build()
    actorSystem.registerOnTermination(client.close())
    client
