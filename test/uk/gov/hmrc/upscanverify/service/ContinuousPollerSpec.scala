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

package uk.gov.hmrc.upscanverify.service

import java.util.concurrent.atomic.AtomicInteger
import org.apache.pekko.actor.ActorSystem
import org.scalatest.concurrent.Eventually
import play.api.inject.DefaultApplicationLifecycle
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration
import uk.gov.hmrc.upscanverify.test.UnitSpec

import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

class ContinuousPollerSpec extends UnitSpec with Eventually:

  val serviceConfiguration = new ServiceConfiguration:
    override def accessKeyId: String = ???

    override def awsRegion: String = ???

    override def secretAccessKey: String = ???

    override def sessionToken: Option[String] = ???

    override def retryInterval: FiniteDuration = 1.second

    override def inboundQueueUrl: String = ???

    override def outboundBucket = ???

    override def useContainerCredentials = ???

    override def quarantineBucket: String = ???

    override def processingBatchSize: Int = ???

    override def allowedMimeTypes(serviceName: String): Option[List[String]] = ???

    override def defaultAllowedMimeTypes: List[String] = ???

    override def inboundQueueVisibilityTimeout: Duration = ???

  "QueuePollingJob" should:
    "continuously poll the queue" in:
      given actorSystem: ActorSystem = ActorSystem()

      val callCount = AtomicInteger(0)

      val orchestrator: PollingJob =
        new PollingJob:
          override def run() =
            Future.successful(callCount.incrementAndGet())

      val serviceLifecycle = DefaultApplicationLifecycle()

      ContinuousPoller(orchestrator, serviceConfiguration)(using actorSystem, serviceLifecycle)

      eventually:
        callCount.get() > 5

      serviceLifecycle.stop()

    "recover from failure after some time" in:
      given actorSystem: ActorSystem = ActorSystem()

      val callCount = AtomicInteger(0)

      val orchestrator: PollingJob =
        new PollingJob:
          override def run() =
            if callCount.get() == 1 then
              Future.failed(RuntimeException("Planned failure"))
            else
              Future.successful(callCount.incrementAndGet())

      val serviceLifecycle = DefaultApplicationLifecycle()

      ContinuousPoller(orchestrator, serviceConfiguration)(using actorSystem, serviceLifecycle)

      eventually:
        callCount.get() > 5

      serviceLifecycle.stop()
