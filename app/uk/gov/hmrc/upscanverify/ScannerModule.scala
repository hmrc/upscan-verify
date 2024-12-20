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

package uk.gov.hmrc.upscanverify

import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.clamav.config.ClamAvConfig
import uk.gov.hmrc.upscanverify.config.{PlayBasedServiceConfiguration, PlayClamAvConfig, ServiceConfiguration}
import uk.gov.hmrc.upscanverify.connector.aws.{PollingJob, SqsConsumer, S3EventParser, S3FileManager}
import uk.gov.hmrc.upscanverify.service._
import uk.gov.hmrc.upscanverify.service.tika.TikaMimeTypeDetector

import java.time.Clock

class ScannerModule extends Module:
  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(
      bind[ServiceConfiguration].to[PlayBasedServiceConfiguration].eagerly(),
      bind[ClamAvConfig        ].to[PlayClamAvConfig].eagerly(),
      bind[MessageParser       ].to[S3EventParser],
      bind[PollingJob          ].to[QueueProcessingJob],
      bind[MessageProcessor    ].to[ScanUploadedFilesFlow],
      bind[SqsConsumer         ].toSelf.eagerly(),
      bind[ScanningService     ].to[ClamAvScanningService],
      bind[FileManager         ].to[S3FileManager],
      bind[RejectionNotifier   ].toInstance(LoggingRejectionNotifier),
      bind[MimeTypeDetector    ].to[TikaMimeTypeDetector],
      bind[ChecksumComputingInputStreamFactory].toInstance(SHA256ChecksumComputingInputStreamFactory),
      bind[Clock               ].toInstance(Clock.systemDefaultZone())
    )
