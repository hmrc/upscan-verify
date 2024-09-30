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

package uk.gov.hmrc.clamav

import org.apache.commons.io.IOUtils
import play.api.Logger
import uk.gov.hmrc.clamav.config.ClamAvConfig
import uk.gov.hmrc.clamav.model.{ClamAvException, Clean, Infected, ScanningResult}
import uk.gov.hmrc.http.logging.LoggingDetails
import util.logging.WithLoggingDetails.withLoggingDetails

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ClamAntiVirusFactory @Inject()(
  clamAvConfig: ClamAvConfig
)(implicit
  ec          : ExecutionContext
):
  def getClient(): ClamAntiVirus =
    new ClamAntiVirusImpl(clamAvConfig)

private[clamav] class ClamAntiVirusImpl(
  clamAvConfig: ClamAvConfig
)(implicit
  ec: ExecutionContext
) extends ClamAntiVirus:

  private val logger                 = Logger(this.getClass)
  private val Handshake              = "zINSTREAM\u0000"
  private val FileCleanResponse      = "stream: OK\u0000"
  private val VirusFoundResponse     = "stream\\: (.+) FOUND\u0000".r
  private val ParseableErrorResponse = "(.+) ERROR\u0000".r

  override def sendAndCheck(
    objectKey  : String,
    inputStream: InputStream,
    length     : Int
  )(implicit
    ld: LoggingDetails,
    ec: ExecutionContext
  ): Future[ScanningResult] =
    if length > 0 then
      ClamAvSocket.withSocket(clamAvConfig): connection =>
        val start = System.currentTimeMillis()
        for
          _               <- sendHandshake(connection)
          handshakeTimeMs =  System.currentTimeMillis()
          _               =  withLoggingDetails(ld)(logger.info(s"Send clamav handshake for Key=[$objectKey] took ${handshakeTimeMs - start}ms"))
          _               <- sendRequest(connection)(inputStream, length)
          sendReqTimeMs   =  System.currentTimeMillis()
          _               =  withLoggingDetails(ld)(logger.info(s"Send clamav request for Key=[$objectKey] took ${sendReqTimeMs - handshakeTimeMs}ms"))
          response        <- readResponse(connection)
          readResTimeMs   =  System.currentTimeMillis()
          _               =  withLoggingDetails(ld)(logger.info(s"Read clamav response for Key=[$objectKey] took ${readResTimeMs - sendReqTimeMs}ms"))
          parsedResponse  <- parseResponse(response)
          _               =  withLoggingDetails(ld)(logger.info(s"Parse clamav response for Key=[$objectKey] took ${System.currentTimeMillis() - readResTimeMs}ms"))
        yield parsedResponse
    else
      Future.successful(Clean)

  private def sendHandshake(connection: Connection)(implicit ec: ExecutionContext) =
    Future:
      connection.out.write(Handshake.getBytes)

  private def sendRequest(connection: Connection)(stream: InputStream, length: Int)(implicit ec: ExecutionContext) =
    Future:
      connection.out.writeInt(length)
      IOUtils.copy(stream, connection.out)
      connection.out.writeInt(0)
      connection.out.flush()

  private def readResponse(connection: Connection): Future[String] =
    Future:
      IOUtils.toString(connection.in, UTF_8)

  private def parseResponse(response: String) =
    response match
      case FileCleanResponse             => Future.successful(Clean)
      case VirusFoundResponse(virus)     => Future.successful(Infected(virus))
      case ParseableErrorResponse(error) => Future.failed(new ClamAvException(error))
      case unparseableResponse           => Future.failed(new ClamAvException(s"Unparseable response from ClamAV: [$unparseableResponse]"))
