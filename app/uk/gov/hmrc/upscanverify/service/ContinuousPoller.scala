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

import org.apache.pekko.event.Logging
import org.apache.pekko.actor._
import play.api.Logging
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration
import uk.gov.hmrc.upscanverify.service.ContinuousPollingActor.Poll

import javax.inject.Inject
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

trait PollingJob:
  def run(): Future[Unit]

  def jobName: String = this.getClass.getName

class ContinuousPoller @Inject()(
  job                 : PollingJob,
  serviceConfiguration: ServiceConfiguration
)(using
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle
) extends Logging:

  logger.info(s"Creating ContinuousPollingActor for PollingJob: [${job.jobName}].")

  private val pollingActor =
    actorSystem.actorOf(ContinuousPollingActor(job, serviceConfiguration.retryInterval), "Poller")

  logger.info(s"Sending initial Poll message to Actor: [${pollingActor.toString}].")
  pollingActor ! Poll


  applicationLifecycle.addStopHook: () =>
    logger.info(s"Sending PoisonPill message to Actor: [${pollingActor.toString}].")
    pollingActor ! PoisonPill
    Future.unit

class ContinuousPollingActor(job: PollingJob, retryInterval: FiniteDuration) extends Actor:

  import context.dispatcher

  val log = Logging(context.system, this)

  override def receive: Receive =
    case Poll =>
      log.debug(s"Polling for job: [${job.jobName}].")
      job.run().andThen:
        case Success(r) =>
          log.debug(s"Polling succeeded for job: [${job.jobName}].")
          self ! Poll
        case Failure(f) =>
          log.error(f, s"Polling failed for job: [${job.jobName}].")
          context.system.scheduler.scheduleOnce(retryInterval, self, Poll)

object ContinuousPollingActor:

  def apply(orchestrator: PollingJob, retryInterval: FiniteDuration): Props =
    Props(new ContinuousPollingActor(orchestrator, retryInterval))

  case object Poll
