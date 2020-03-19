package modules

import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.sqs.AmazonSQS
import javax.inject.Provider
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.{Configuration, Environment}
import play.api.inject.{Binding, Module}

import scala.reflect.ClassTag

class MockAWSClientModule extends Module {
  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(
      bind[AmazonSQS].to(new MockProvider[AmazonSQS]()),
      bind[AmazonS3].to(new MockProvider[AmazonS3]()),
      bind[AmazonEC2].to(new MockProvider[AmazonEC2]())
    )
}

class MockProvider[T <: AnyRef](implicit classTag: ClassTag[T]) extends Provider[T] {
  override def get(): T = mock[T]
}
