package modules

import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.sqs.AmazonSQS
import javax.inject.Provider
import org.scalatest.mockito.MockitoSugar.mock
import play.api.{Configuration, Environment}
import play.api.inject.{Binding, Module}

class MockAWSClientModule extends Module {
  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(
      bind[AmazonSQS].toProvider[MockAmazonSQSProvider],
      bind[AmazonS3].toProvider[MockAmazonS3Provider],
      bind[AmazonEC2].toProvider[MockAmazonEC2Provider]
    )
}

class MockAmazonSQSProvider extends Provider[AmazonSQS] {
  override def get(): AmazonSQS = mock[AmazonSQS]
}

class MockAmazonS3Provider extends Provider[AmazonS3] {
  override def get(): AmazonS3 = mock[AmazonS3]
}

class MockAmazonEC2Provider extends Provider[AmazonEC2] {
  override def get(): AmazonEC2 = mock[AmazonEC2]
}
/*
class MockProvider[T] extends Provider[T] {
  override def get(): T = mock[T]
}
*/