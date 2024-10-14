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

package uk.gov.hmrc.upscanverify.config

import com.typesafe.config.ConfigObject
import play.api.{ConfigLoader, Configuration}

import javax.inject.Inject
import scala.jdk.CollectionConverters._
import scala.concurrent.duration.{Duration, FiniteDuration}

trait ServiceConfiguration:
  def quarantineBucket: String

  def retryInterval: FiniteDuration

  def inboundQueueUrl: String

  def inboundQueueVisibilityTimeout: Duration

  def accessKeyId: String

  def secretAccessKey: String

  def sessionToken: Option[String]

  def outboundBucket: String

  def awsRegion: String

  def useContainerCredentials: Boolean

  def processingBatchSize: Int

  def waitTime: Duration

  def allowedMimeTypes(serviceName: String): Option[List[String]]

  def defaultAllowedMimeTypes: List[String]

class PlayBasedServiceConfiguration @Inject()(configuration: Configuration) extends ServiceConfiguration:

  override def inboundQueueUrl: String =
    configuration.get[String]("aws.sqs.queue.inbound")

  override def inboundQueueVisibilityTimeout: Duration =
    configuration.get[Duration]("aws.sqs.queue.visibilityTimeout")

  override def awsRegion: String =
    configuration.get[String]("aws.s3.region")

  override def useContainerCredentials: Boolean =
    configuration.get[Boolean]("aws.useContainerCredentials")

  override def accessKeyId: String =
    configuration.get[String]("aws.accessKeyId")

  override def secretAccessKey: String =
    configuration.get[String]("aws.secretAccessKey")

  override def sessionToken: Option[String] =
    configuration.getOptional[String]("aws.sessionToken")

  override def retryInterval: FiniteDuration =
    configuration.get[FiniteDuration]("aws.sqs.retry.interval")

  override def outboundBucket: String =
    configuration.get[String]("aws.s3.bucket.outbound")

  override def quarantineBucket: String =
    configuration.get[String]("aws.s3.bucket.quarantine")

  override def processingBatchSize: Int =
    configuration.get[Int]("processingBatchSize")

  override def waitTime: Duration =
    configuration.get[FiniteDuration]("aws.sqs.waitTime")

  override def allowedMimeTypes(serviceName: String): Option[List[String]] =
    consumingServicesConfiguration.serviceConfigurations
      .find(_.serviceName == serviceName)
      .map(_.allowedMimeTypes)

  override def defaultAllowedMimeTypes: List[String] =
    configuration.get[String]("fileTypesFilter.defaultAllowedMimeTypes")
      .split(",").toList

  private case class AllowedMimeTypes(
    serviceName     : String,
    allowedMimeTypes: List[String]
  )
  private case class ConsumingServicesConfiguration(
    serviceConfigurations: List[AllowedMimeTypes]
  )

  private val consumingServicesConfiguration: ConsumingServicesConfiguration =
    def toPerServiceConfiguration(consumingServiceConfig: ConfigObject): Either[String, AllowedMimeTypes] =
      val serviceAsMap = consumingServiceConfig.unwrapped.asScala
      (serviceAsMap.get("user-agent"), serviceAsMap.get("mime-types")) match
        case (Some(service: String), Some(mimeTypes: String)) =>
          Right(AllowedMimeTypes(service, mimeTypes.split(",").toList))
        case _ =>
          Left(s"Could not parse config object for services configuration: $serviceAsMap")

    given ConfigLoader[List[ConfigObject]] = ConfigLoader(_.getObjectList).map(_.asScala.toList)

    val key                     = "fileTypesFilter.allowedMimeTypes"
    val servicesConfigArray     = configuration.getOptional[List[ConfigObject]](key).getOrElse(Nil)
    val serviceAllowedMimeTypes = servicesConfigArray.map(toPerServiceConfiguration)

    serviceAllowedMimeTypes.collect({ case Left(error) => error }) match
      case Nil =>
        val allowed = serviceAllowedMimeTypes.collect({ case Right(allowedMimeTypes) => allowedMimeTypes })
        ConsumingServicesConfiguration(allowed)
      case errors =>
        throw Exception(s"Configuration key not correctly configured: $key, errors: ${errors.mkString(", ")}")
