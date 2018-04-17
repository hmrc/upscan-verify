package modules

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth._
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.Protocol
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.{AmazonEC2, AmazonEC2ClientBuilder}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import config.ServiceConfiguration
import javax.inject.{Inject, Provider}
import play.api.{Configuration, Environment}
import play.api.inject.{Binding, Module}

class LocalAWSClientModule extends Module {
  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(
      bind[ClientConfiguration].toProvider[LocalClientConfigurationProvider],
      bind[EndpointConfiguration].toProvider[LocalEndpointConfigurationProvider],
      bind[AWSCredentialsProvider].toProvider[LocalAWSCredentialsProviderProvider],
      bind[AmazonSQS].toProvider[LocalAmazonSQSProvider],
      bind[AmazonS3].toProvider[LocalAmazonS3Provider],
      bind[AmazonEC2].toProvider[LocalAmazonEC2Provider]
    )
}

class LocalClientConfigurationProvider @Inject()(configuration: ServiceConfiguration) extends Provider[ClientConfiguration] {
  override def get(): ClientConfiguration =
    new ClientConfiguration().withProtocol(Protocol.HTTP)//.withSignerOverride(SignerFactory.VERSION_THREE_SIGNER)
}

class LocalAWSCredentialsProviderProvider @Inject()(configuration: ServiceConfiguration) extends Provider[AWSCredentialsProvider] {
  override def get(): AWSCredentialsProvider =
    new AWSStaticCredentialsProvider(new AnonymousAWSCredentials() /*new BasicAWSCredentials("myAccessKey", "mySecretKey")*/)
}

class LocalEndpointConfigurationProvider extends Provider[EndpointConfiguration] {
  override def get(): EndpointConfiguration = new EndpointConfiguration(s"http://localhost:9000", "blah")
}

class LocalAmazonSQSProvider @Inject()(credentialsProvider: AWSCredentialsProvider,
//                                       clientConfiguration: ClientConfiguration,
                                       endpointConfiguration: EndpointConfiguration) extends Provider[AmazonSQS] {
  override def get(): AmazonSQS =
    AmazonSQSClientBuilder
      .standard()
//      .withClientConfiguration(clientConfiguration)
      .withCredentials(credentialsProvider)
      .withEndpointConfiguration(endpointConfiguration)
      .build()
}

class LocalAmazonS3Provider @Inject()(credentialsProvider: AWSCredentialsProvider,
//                                      clientConfiguration: ClientConfiguration,
                                      endpointConfiguration: EndpointConfiguration) extends Provider[AmazonS3] {
  override def get(): AmazonS3 =
    AmazonS3ClientBuilder
      .standard()
//      .withClientConfiguration(clientConfiguration)
      .withCredentials(credentialsProvider)
      .withEndpointConfiguration(endpointConfiguration)
      .withPathStyleAccessEnabled(true)
      .build()
}

class LocalAmazonEC2Provider @Inject()(credentialsProvider: AWSCredentialsProvider,
//                                       clientConfiguration: ClientConfiguration,
                                       endpointConfiguration: EndpointConfiguration) extends Provider[AmazonEC2] {
  override def get(): AmazonEC2 =
    AmazonEC2ClientBuilder
      .standard()
//      .withClientConfiguration(clientConfiguration)
      .withCredentials(credentialsProvider)
      .withEndpointConfiguration(endpointConfiguration)
      .build()
}
