import play.core.PlayVersion
import sbt._

object AppDependencies {

  private val compile = Seq(
    "uk.gov.hmrc"       %% "bootstrap-play-26" % "1.5.0",
    "org.typelevel"     %% "cats-core"         % "2.0.0",
    "com.amazonaws"      % "aws-java-sdk-s3"   % "1.11.699",
    "com.amazonaws"      % "aws-java-sdk-sqs"  % "1.11.699",
    "com.amazonaws"      % "aws-java-sdk-ec2"   % "1.11.699",
    "org.apache.tika"    % "tika-core"          % "1.23",
    "org.apache.tika"    % "tika-parsers"       % "1.23",
    "commons-io"         % "commons-io"        % "2.6"
  )

  private val test = Seq(
    "uk.gov.hmrc"            %% "hmrctest"                    % "3.9.0-play-26"     % s"$Test,$IntegrationTest",
    "com.typesafe.play"      %% "play-test"                   % PlayVersion.current % s"$Test,$IntegrationTest",
    "org.scalatest"          %% "scalatest"                   % "3.0.8"             % s"$Test,$IntegrationTest",
    "org.scalatestplus.play" %% "scalatestplus-play"          % "3.1.2"             % s"$Test,$IntegrationTest",
    "org.pegdown"             % "pegdown"                     % "1.6.0"             % s"$Test,$IntegrationTest",
    "org.mockito"             % "mockito-core"                % "3.3.0"             % s"$Test,$IntegrationTest"
  )

  def apply(): Seq[ModuleID] = compile ++ test
}
