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

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{ChangeMessageVisibilityRequest, DeleteMessageRequest, Message, MessageSystemAttributeName, ReceiveMessageRequest, SqsException}
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import play.api.Logging
import uk.gov.hmrc.play.http.logging.Mdc
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration

import java.util.concurrent.{CompletableFuture, CompletionException}
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.util.control.NonFatal

trait PollingJob:
  def processMessage(message: Message): Future[Boolean]

  def jobName: String = this.getClass.getName

class SqsConsumer @Inject()(
  sqsClient           : SqsAsyncClient,
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
        ReceiveMessageRequest.builder()
          .queueUrl(serviceConfiguration.inboundQueueUrl)
          .maxNumberOfMessages(serviceConfiguration.processingBatchSize)
          .waitTimeSeconds(serviceConfiguration.waitTime.toSeconds.toInt)
          .visibilityTimeout(serviceConfiguration.inboundQueueVisibilityTimeout.toSeconds.toInt)
          .messageSystemAttributeNames(MessageSystemAttributeName.SENT_TIMESTAMP)
          .build()
      .mapAsync(parallelism = 1)(getMessages)
      .mapConcat(identity)
      .mapAsync(parallelism = 1): message =>
        job.processMessage(message)
          .flatMap: isHandled =>
            if isHandled then
              logger.debug(s"Message ${message.messageId} was processed")
              deleteMessage(message)
            else
              // message will return to queue after retryInterval
              // Note, we previously stopped processing *all* messages on this instance until the retryInterval
              // We probably only need to do this for exceptions that are known to affect all messages
              // This could be done by completing Future.unit after a timeout (e.g. complete a promise with `context.system.scheduler.scheduleOnce`)
              logger.debug(s"Failed to process ${message.messageId}")
              returnMessage(message)
          .recover:
            case NonFatal(e) =>
              logger.error(s"Failed to process message", e)
              returnMessage(message)
      .run()
      .andThen: res =>
        logger.info(s"Queue terminated: $res - restarting")
        actorSystem.scheduler.scheduleOnce(10.seconds)(runQueue())

  runQueue()

  private def toScala[A](f: CompletableFuture[A]): Future[A] =
    Mdc
      .preservingMdc:
        f.asScala
      .recoverWith:
        case e: CompletionException => Future.failed(e.getCause)

  private def getMessages(req: ReceiveMessageRequest): Future[Seq[Message]] =
    logger.info("receiving messages")
    toScala:
      sqsClient.receiveMessage(req)
    .map(_.messages.asScala.toSeq)
    .map: res =>
      logger.info(s"received ${res.size} messages")
      res

  private def deleteMessage(message: Message): Future[Unit] =
    toScala:
      sqsClient
        .deleteMessage:
          DeleteMessageRequest.builder()
            .queueUrl(serviceConfiguration.inboundQueueUrl)
            .receiptHandle(message.receiptHandle)
            .build()
    .map: _ =>
      logger.debug:
        s"Deleted message '${message.messageId()}' from Queue: [${serviceConfiguration.inboundQueueUrl}], for receiptHandle: [${message.receiptHandle}]."
    .recover:
      case ex: SqsException if ex.statusCode() == 404 =>
        logger.warn(
          s"Unable to deleted message '${message.messageId()}' from Queue: [${serviceConfiguration.inboundQueueUrl}], for receiptHandle: [${message.receiptHandle}] (already processed)",
          ex
        )

  private def returnMessage(message: Message): Future[Unit] =
    toScala:
      sqsClient
        .changeMessageVisibility:
          ChangeMessageVisibilityRequest.builder()
            .queueUrl(serviceConfiguration.inboundQueueUrl)
            .receiptHandle(message.receiptHandle)
            .visibilityTimeout(serviceConfiguration.retryInterval.toSeconds.toInt)
            .build()
    .map: _ =>
      logger.debug:
        s"Returned message back to the queue (after ${serviceConfiguration.retryInterval}): [${serviceConfiguration.inboundQueueUrl}], for receiptHandle: [${message.receiptHandle}]."
