import play.core.PlayVersion
import sbt._

object AppDependencies {

  private val compile = Seq(
    "uk.gov.hmrc"       %% "bootstrap-backend-play-27" % "2.14.0",
    "org.typelevel"     %% "cats-core"                 % "2.1.1",
    "com.amazonaws"      % "aws-java-sdk-s3"           % "1.11.769",
    "com.amazonaws"      % "aws-java-sdk-sqs"          % "1.11.769",
    "com.amazonaws"      % "aws-java-sdk-ec2"          % "1.11.769",
    "org.apache.tika"    % "tika-core"                 % "1.24.1",
    "org.apache.tika"    % "tika-parsers"              % "1.24.1",
    "commons-io"         % "commons-io"                % "2.6"
  )

  private val test = Seq(
    "com.typesafe.play"      %% "play-test"                   % PlayVersion.current % s"$Test,$IntegrationTest",
    "org.scalatest"          %% "scalatest"                   % "3.1.1"             % s"$Test,$IntegrationTest",
    "org.scalatestplus.play" %% "scalatestplus-play"          % "4.0.3"             % s"$Test,$IntegrationTest",
    "com.vladsch.flexmark"    % "flexmark-all"                % "0.35.10"           % s"$Test,$IntegrationTest",
    "org.mockito"            %% "mockito-scala-scalatest"     % "1.13.10"           % s"$Test,$IntegrationTest"
  )

  def apply(): Seq[ModuleID] = compile ++ test
}
