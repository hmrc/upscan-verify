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

import cats.data.EitherT
import play.api.Logger
import uk.gov.hmrc.upscanverify.model._

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class FileCheckingService @Inject()(
  fileManager            : FileManager,
  virusScanningService   : ScanningService,
  fileTypeCheckingService: FileTypeCheckingService
)(using
  ExecutionContext
):

  private val logger = Logger(getClass)

  def check(
    location      : S3ObjectLocation,
    objectMetadata: InboundObjectMetadata
  ): Future[VerifyResult] =

    logger.debug(s"Checking upload Key=[${location.objectKey}]")

    (for
       noVirusFound <- EitherT
                         .apply:
                           // TODO can we only interact with fileManager in one place, getting both inboundObjectMetadata and
                           // content (as a Stream). We can broadcast the stream to both clam and tika (which may also avoid the
                           //  warning where tika does not consume the whole stream)
                           fileManager.withObjectContent(location): objectContent =>
                             virusScanningService.scan(location, objectContent, objectMetadata)
                         .leftMap(VerifyResult.FileRejected.VirusScanFailure.apply)
       fileAllowed <- EitherT
                        .apply:
                          fileManager.withObjectContent(location): objectContent =>
                            fileTypeCheckingService.scan(location, objectContent, objectMetadata)
                        .leftMap(fileTypeError => VerifyResult.FileRejected.FileTypeFailure(noVirusFound, fileTypeError))
     yield
       VerifyResult.FileValidationSuccess(noVirusFound, fileAllowed)
    ).value
