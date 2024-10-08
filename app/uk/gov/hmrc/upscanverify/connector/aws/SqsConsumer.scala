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

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, Message, ReceiveMessageRequest}
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import play.api.Logging
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.util.control.NonFatal

trait PollingJob:
  def processMessage(message: Message): Future[Unit]

  def jobName: String = this.getClass.getName

class SqsConsumer @Inject()(
  sqsClient           : AmazonSQS,
  job                 : PollingJob, // QueueProcessingJob
  serviceConfiguration: ServiceConfiguration
)(using
  actorSystem         : ActorSystem,
  ec                  : ExecutionContext
) extends Logging:

  logger.info(s"Starting SQS consumption for PollingJob: [${job.jobName}].")

  def runQueue(): Future[Done] =
    Source
      .repeat:
        ReceiveMessageRequest(serviceConfiguration.inboundQueueUrl)
          .withMaxNumberOfMessages(serviceConfiguration.processingBatchSize)
          .withWaitTimeSeconds(20)
          .withVisibilityTimeout(serviceConfiguration.inboundQueueVisibilityTimeout.toSeconds.toInt)
          .withAttributeNames("All")
      .mapAsync(parallelism = 1)(getMessages)
      .mapConcat(xs => xs)
      .mapAsync(parallelism = 1): message =>
        processMessage(message).flatMap:
          case MessageAction.Delete(message) => deleteMessage(message)
          case MessageAction.Ignore(_)       => Future.unit // message will appear on queue after visibility timeout
                                                // alternatively: returnMessage(message) (maybe after `serviceConfiguration.retryInterval`)
                                                // set the VisibilityTimeout to 0 seconds through the ChangeMessageVisibility action. This immediately makes the message available for other consumers to process.
        .recover:
          case NonFatal(e)                   => logger.error(s"Failed to process messages", e)
      .run()
      .andThen: res =>
        logger.info(s"Queue terminated: $res - restarting")
        actorSystem.scheduler.scheduleOnce(10.seconds)(runQueue())

  runQueue()

  private def getMessages(req: ReceiveMessageRequest): Future[Seq[Message]] =
    logger.info("receiving messages")
    Future
      .apply:
        sqsClient.receiveMessage(req)
      .map(_.getMessages.asScala.toSeq)
      .map: res =>
        logger.info(s"received ${res.size} messages")
        res

  private def deleteMessage(message: Message): Future[Unit] =
    Future
      .apply:
        sqsClient
          .deleteMessage:
            DeleteMessageRequest(serviceConfiguration.inboundQueueUrl, message.getReceiptHandle)
      .map: _ =>
        logger.debug:
          s"Deleted message from Queue: [${serviceConfiguration.inboundQueueUrl}], for receiptHandle: [${message.getReceiptHandle}]."

  def processMessage(message: Message): Future[MessageAction] =
    logger.debug(s"Polling for job: [${job.jobName}].")
    job.processMessage(message)
      .map: _ =>
        logger.debug(s"Polling succeeded for job: [${job.jobName}].")
        MessageAction.Delete(message) // proceed to next one
      .recover: e =>
        logger.error(s"Polling failed for job: [${job.jobName}].", e)
        MessageAction.Ignore(message)


enum MessageAction:
  case Delete(message: Message)
  case Ignore(message: Message)
