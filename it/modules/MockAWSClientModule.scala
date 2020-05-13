package modules

import java.util.Collections.emptyList

import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{ReceiveMessageRequest, ReceiveMessageResult}
import javax.inject.Provider
import org.mockito.ArgumentMatchersSugar.any
import org.mockito.MockitoSugar.{mock, when}
import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}

import scala.reflect.ClassTag

class MockAWSClientModule extends Module {

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(
      bind[AmazonSQS].to(new EmptyMessageAmazonSQSProvider),
      bind[AmazonS3].to(new MockProvider[AmazonS3]()),
      bind[AmazonEC2].to(new MockProvider[AmazonEC2]())
    )
}

/*
 * Avoid NullPointerException from SQS on integration test start as ContinuousPoller attempts to receive messages.
 */
private class EmptyMessageAmazonSQSProvider extends Provider[AmazonSQS] {
  override def get(): AmazonSQS = {
    val emptyReceiveMessageResult = makeEmptyReceiveMessageResult
    val amazonSQS = mock[AmazonSQS]
    when(amazonSQS.receiveMessage(any[ReceiveMessageRequest])).thenReturn(emptyReceiveMessageResult)
    amazonSQS
  }

  private def makeEmptyReceiveMessageResult: ReceiveMessageResult = {
    val result = new ReceiveMessageResult()
    result.setMessages(emptyList())
    result
  }
}

private class MockProvider[T <: AnyRef](implicit classTag: ClassTag[T]) extends Provider[T] {
  override def get(): T = mock[T]
}
