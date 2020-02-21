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

package connectors.aws

import java.time.Instant

import model.{FileUploadEvent, Message, S3ObjectLocation}
import org.scalatest.Matchers
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class S3EventMessageParserSpec extends UnitSpec with Matchers {

  val parser = new S3EventParser()

  "MessageParser" should {
    "properly parse valid S3 event message" in {

      Await
        .result(parser.parse(Message("ID", sampleMessageWithoutVersioning, "HANDLE", Instant.now())), 2 seconds) shouldBe FileUploadEvent(
        S3ObjectLocation("hmrc-upscan-live-transient", "acabd94b-4d74-4b04-a0ca-1914950f9c02", None),
        "127.0.0.1")

      Await
        .result(parser.parse(Message("ID", sampleMessageWithVersioning, "HANDLE", Instant.now())), 2 seconds) shouldBe FileUploadEvent(
        S3ObjectLocation(
          "hmrc-upscan-live-transient",
          "acabd94b-4d74-4b04-a0ca-1914950f9c02",
          Some("laxvaXuSOlPXfoPi_gNmg5B4_AnVuBbW")),
        "127.0.0.1")

    }

    "properly parse valid S3 event message triggered by copying object between buckets" in {

      Await.result(parser.parse(Message("ID", sampleCopyMessage, "HANDLE", Instant.now())), 2 seconds) shouldBe FileUploadEvent(
        S3ObjectLocation("fus-outbound-759b74ce43947f5f4c91aeddc3e5bad3", "16d77f7a-1f42-4bc2-aa7c-3e1b57b75b26", None),
        "127.0.0.1")

    }

    "return failure for test message" in {
      val result: Future[FileUploadEvent] = parser.parse(Message("ID1", testMessage, "HANDLE", Instant.now()))
      Await.ready(result, 2 seconds)
      result.value.get.isSuccess shouldBe false
    }

    "return unparseable message for S3 message other than upload" in {
      val result: Future[FileUploadEvent] = parser.parse(Message("ID1", others3message, "HANDLE", Instant.now()))
      Await.ready(result, 2 seconds)
      result.value.get.isSuccess shouldBe false
    }

    "return unparseable message for S3 message with invalid JSON" in {
      val result: Future[FileUploadEvent] = parser.parse(Message("ID1", "$>>>>", "HANDLE", Instant.now()))
      Await.ready(result, 2 seconds)
      result.value.get.isSuccess shouldBe false
    }
  }

  val sampleMessageWithoutVersioning =
    """ 
                        |{
                        |  "Records": [
                        |    {
                        |      "eventVersion": "2.0",
                        |      "eventSource": "aws:s3",
                        |      "awsRegion": "eu-west-2",
                        |      "eventTime": "2018-02-23T08:02:46.764Z",
                        |      "eventName": "ObjectCreated:Post",
                        |      "userIdentity": {
                        |        "principalId": "AWS:AIDAIIELOEELZHP2AGCQU"
                        |      },
                        |      "requestParameters": {
                        |        "sourceIPAddress": "127.0.0.1"
                        |      },
                        |      "responseElements": {
                        |        "x-amz-request-id": "119DF70CC1EA8B55",
                        |        "x-amz-id-2": "KVdXT87To7UrY5a1XT4hZUgmK6cOz02WTIxxnUCT3/2accPt5fpq23/Cb0i/w23J6N4btF1NaXw="
                        |      },
                        |      "s3": {
                        |        "s3SchemaVersion": "1.0",
                        |        "configurationId": "NotifyFileUploadedEvent",
                        |        "bucket": {
                        |          "name": "hmrc-upscan-live-transient",
                        |          "ownerIdentity": {
                        |            "principalId": "A2XP2K6B42LFR5"
                        |          },
                        |          "arn": "arn:aws:s3:::hmrc-upscan-live-transient"
                        |        },
                        |        "object": {
                        |          "key": "acabd94b-4d74-4b04-a0ca-1914950f9c02",
                        |          "size": 1024,
                        |          "eTag": "d54fcd247258c454fc6da20eac8aee86",
                        |          "versionId": "null",
                        |          "sequencer": "005A8FCAA6B34C4355"
                        |        }
                        |      }
                        |    }
                        |  ]
                        |}
                        |
  """.stripMargin

  val sampleMessageWithVersioning =
    """ 
      |{
      |  "Records": [
      |    {
      |      "eventVersion": "2.0",
      |      "eventSource": "aws:s3",
      |      "awsRegion": "eu-west-2",
      |      "eventTime": "2018-02-23T08:02:46.764Z",
      |      "eventName": "ObjectCreated:Post",
      |      "userIdentity": {
      |        "principalId": "AWS:AIDAIIELOEELZHP2AGCQU"
      |      },
      |      "requestParameters": {
      |        "sourceIPAddress": "127.0.0.1"
      |      },
      |      "responseElements": {
      |        "x-amz-request-id": "119DF70CC1EA8B55",
      |        "x-amz-id-2": "KVdXT87To7UrY5a1XT4hZUgmK6cOz02WTIxxnUCT3/2accPt5fpq23/Cb0i/w23J6N4btF1NaXw="
      |      },
      |      "s3": {
      |        "s3SchemaVersion": "1.0",
      |        "configurationId": "NotifyFileUploadedEvent",
      |        "bucket": {
      |          "name": "hmrc-upscan-live-transient",
      |          "ownerIdentity": {
      |            "principalId": "A2XP2K6B42LFR5"
      |          },
      |          "arn": "arn:aws:s3:::hmrc-upscan-live-transient"
      |        },
      |        "object": {
      |          "key": "acabd94b-4d74-4b04-a0ca-1914950f9c02",
      |          "size": 1024,
      |          "eTag": "d54fcd247258c454fc6da20eac8aee86",
      |          "versionId": "laxvaXuSOlPXfoPi_gNmg5B4_AnVuBbW",
      |          "sequencer": "005A8FCAA6B34C4355"
      |        }
      |      }
      |    }
      |  ]
      |}
      |
  """.stripMargin

  val sampleCopyMessage =
    """
      |{
      |  "Records": [
      |    {
      |      "eventVersion": "2.0",
      |      "eventSource": "aws:s3",
      |      "awsRegion": "eu-west-2",
      |      "eventTime": "2018-03-20T15:16:04.019Z",
      |      "eventName": "ObjectCreated:Copy",
      |      "userIdentity": {
      |        "principalId": "AWS:AROAI3II5VHGEMGMCYKJ2:botocore-session-1521558860"
      |      },
      |      "requestParameters": {
      |        "sourceIPAddress": "127.0.0.1"
      |      },
      |      "responseElements": {
      |        "x-amz-request-id": "0320AA5D161796DD",
      |        "x-amz-id-2": "s3zU1l5uKGPieJ3Wd/BWZFd4wQdPcMXNMNrdEf2JU2vLOCv2TFheeGLzR06/9EZeCMrXY/JCWgE="
      |      },
      |      "s3": {
      |        "s3SchemaVersion": "1.0",
      |        "configurationId": "tf-s3-queue-20180307174600377300000002",
      |        "bucket": {
      |          "name": "fus-outbound-759b74ce43947f5f4c91aeddc3e5bad3",
      |          "ownerIdentity": {
      |            "principalId": "A1CP2HAXWD42V9"
      |          },
      |          "arn": "arn:aws:s3:::fus-outbound-759b74ce43947f5f4c91aeddc3e5bad3"
      |        },
      |        "object": {
      |          "key": "16d77f7a-1f42-4bc2-aa7c-3e1b57b75b26",
      |          "size": 1024,
      |          "versionId": "null",
      |          "sequencer": "005AB125B3E2B697B1"
      |        }
      |      }
      |    }
      |  ]
      |}
      |
  """.stripMargin

  val testMessage =
    """
      |{
      |  "Service": "Amazon S3",
      |  "Event": "s3:TestEvent",
      |  "Time": "2018-02-27T15:00:05.107Z",
      |  "Bucket": "fus-outbound-8264ee52f589f4c0191aa94f87aa1aeb",
      |  "RequestId": "EFEB788EC1A4BA5F",
      |  "HostId": "kycuNaNP3tQ+vpr6Dt4AlFge9F2R3HVxYiCA4istv9+PzMfn07zDyIxyvzuk2T4PMfPEPbAtXT8="
      |}
      |
    """.stripMargin

  val others3message =
    """ 
      |{
      |  "Records": [
      |    {
      |      "eventVersion": "2.0",
      |      "eventSource": "aws:s3",
      |      "awsRegion": "eu-west-2",
      |      "eventTime": "2018-02-23T08:02:46.764Z",
      |      "eventName": "ObjectDeleted:Delete",
      |      "userIdentity": {
      |        "principalId": "AWS:AIDAIIELOEELZHP2AGCQU"
      |      },
      |      "requestParameters": {
      |        "sourceIPAddress": "someIp"
      |      },
      |      "responseElements": {
      |        "x-amz-request-id": "119DF70CC1EA8B55",
      |        "x-amz-id-2": "KVdXT87To7UrY5a1XT4hZUgmK6cOz02WTIxxnUCT3/2accPt5fpq23/Cb0i/w23J6N4btF1NaXw="
      |      },
      |      "s3": {
      |        "s3SchemaVersion": "1.0",
      |        "configurationId": "NotifyFileUploadedEvent",
      |        "bucket": {
      |          "name": "hmrc-upscan-live-transient",
      |          "ownerIdentity": {
      |            "principalId": "A2XP2K6B42LFR5"
      |          },
      |          "arn": "arn:aws:s3:::hmrc-upscan-live-transient"
      |        },
      |        "object": {
      |          "key": "acabd94b-4d74-4b04-a0ca-1914950f9c02",
      |          "size": 1024,
      |          "eTag": "d54fcd247258c454fc6da20eac8aee86",
      |          "versionId": "laxvaXuSOlPXfoPi_gNmg5B4_AnVuBbW",
      |          "sequencer": "005A8FCAA6B34C4355"
      |        }
      |      }
      |    }
      |  ]
      |}
      |
  """.stripMargin

}
