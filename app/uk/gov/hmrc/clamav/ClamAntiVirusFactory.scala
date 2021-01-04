/*
 * Copyright 2021 HM Revenue & Customs
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

import uk.gov.hmrc.clamav.model._

import java.io.InputStream
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ClamAntiVirusFactory @Inject()(clamAvIO: ClamAvIO)(implicit ec: ExecutionContext) {
  def getClient(): ClamAntiVirus = new ClamAntiVirusImpl(clamAvIO)
}

private[clamav] class ClamAntiVirusImpl(clamavIO: ClamAvIO)(implicit ec: ExecutionContext)
    extends ClamAntiVirus {

  import clamavIO._

  private val StartInstreamHandshakeCommand = "zINSTREAM\u0000"
  private val StatsCommand                  = "zSTATS\u0000"
  private val FileCleanResponse             = "stream: OK\u0000"
  private val VirusFoundResponse            = "stream\\: (.+) FOUND\u0000".r
  private val ParseableErrorResponse        = "(.+) ERROR\u0000".r
  private val queueRegex                    = "(?s).*QUEUE: (\\d+) items.*".r
  private val threadsRegex                  = "(?s).*THREADS: live (\\d+)  idle (\\d+) max (\\d+) .*".r

  override def scanInputStream(inputStream: InputStream, length: Int): Future[ScanningResult] =
    if (length > 0) {
      withSocket { implicit connection =>
        for {
          _              <- sendCommand(StartInstreamHandshakeCommand)
          _              <- sendData(inputStream, length)
          response       <- readResponse
          parsedResponse <- parseScanResponse(response)
        } yield parsedResponse
      }
    } else {
      Future.successful(Clean)
    }

  override def stats: Future[ClamAvStats] = withSocket { implicit connection =>
    for {
      _        <- sendCommand(StatsCommand)
      response <- readResponse
      stats    <- parseStatsResponse(response)
    } yield stats
  }

  private def parseStatsResponse(response: String): Future[ClamAvStats] =
    Future
      .fromTry {
        Try {
          val queueRegex(queueLength)       = response
          val threadsRegex(live, idle, max) = response
          ClamAvStats(queueLength.toInt, idle.toInt, live.toInt, max.toInt)
        }
      }
      .recoverWith {
        case ex => Future.failed(new RuntimeException(s"Unparseable stats response from ClamAV: [$response]", ex))
      }


  private def parseScanResponse(response: String) =
    response match {
      case FileCleanResponse             => Future.successful(Clean)
      case VirusFoundResponse(virus)     => Future.successful(Infected(virus))
      case ParseableErrorResponse(error) => Future.failed(ClamAvException(error))
      case unparseableResponse =>
        Future.failed(ClamAvException(s"Unparseable scan response from ClamAV: $unparseableResponse"))
    }
}