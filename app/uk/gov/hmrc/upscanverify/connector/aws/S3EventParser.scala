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

import play.api.Logging
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.libs.json.Reads._
import uk.gov.hmrc.upscanverify.model.{FileUploadEvent, Message, S3ObjectLocation}
import uk.gov.hmrc.upscanverify.service._
import uk.gov.hmrc.upscanverify.util.logging.LoggingUtils

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class S3EventParser @Inject() ()(using ExecutionContext) extends MessageParser with Logging:

  case class S3EventNotification(
    records: Seq[S3EventNotificationRecord]
  )

  case class S3EventNotificationRecord(
    eventVersion     : String,
    eventSource      : String,
    awsRegion        : String,
    eventTime        : String,
    eventName        : String,
    requestParameters: RequestParameters,
    s3               : S3Details
  )

  case class RequestParameters(
    sourceIPAddress: String
  )

  case class S3Details(
    bucketName: String,
    objectKey : String,
    versionId : Option[String]
  )

  given Reads[S3Details] =
    ( (__ \ "bucket" \ "name"     ).read[String]
    ~ (__ \ "object" \ "key"      ).read[String]
    ~ (__ \ "object" \ "versionId").read[String].map(Some(_).filterNot(_.equals("null")))
    )(S3Details.apply _)

  given Reads[RequestParameters] =
    Json.reads[RequestParameters]

  given Reads[S3EventNotificationRecord] =
    Json.reads[S3EventNotificationRecord]

  given Reads[S3EventNotification] =
    (__ \ "Records").read[Seq[S3EventNotificationRecord]].map(S3EventNotification.apply)

  override def parse(message: Message): Future[FileUploadEvent] =
    for
      json               <- Future.fromTry(Try(Json.parse(message.body)))
      deserializedJson   <- asFuture(json.validate[S3EventNotification])
      interpretedMessage <- interpretS3EventMessage(deserializedJson)
    yield interpretedMessage

  private def asFuture[T](input: JsResult[T]): Future[T] =
    input.fold(
      errors => Future.failed(Exception(s"Cannot parse the message: [$errors].")),
      result => Future.successful(result)
    )

  private val ObjectCreatedEventPattern = "ObjectCreated\\:.*".r

  private def interpretS3EventMessage(result: S3EventNotification): Future[FileUploadEvent] =
    result.records match
      case Seq(S3EventNotificationRecord(_, "aws:s3", _, _, ObjectCreatedEventPattern(), requestParameters, s3Details)) =>
        val event =
          FileUploadEvent(
            S3ObjectLocation(s3Details.bucketName, s3Details.objectKey, s3Details.versionId),
            requestParameters.sourceIPAddress
          )

        // TODO refactor so that the MessageContext is only created once
        LoggingUtils.withMdc(MessageContext(event.location.objectKey)):
          logger.info(s"Created FileUploadEvent for Key=[${event.location.objectKey}].")

        Future.successful(event)

      case _ =>
        Future.failed(Exception(s"Unexpected records in event: [${result.records}]."))
