/*
 * Copyright 2020 HM Revenue & Customs
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

package util.logging

import org.slf4j.MDC
import uk.gov.hmrc.http.logging.LoggingDetails

object WithLoggingDetails {
  def withLoggingDetails[T](context: LoggingDetails)(block: => T): T = {
    val previous = Option(MDC.getCopyOfContextMap)
    context.mdcData.foreach {
      case (k, v) => MDC.put(k, v)
    }
    try {
      block
    } finally {
      MDC.clear()
      previous.foreach(MDC.setContextMap)
    }
  }

}
