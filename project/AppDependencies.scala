import sbt._

object AppDependencies {

  private val bootstrapVersion = "5.18.0"

  private val compile = Seq(
    "uk.gov.hmrc"       %% "bootstrap-backend-play-28" % bootstrapVersion,
    "org.typelevel"     %% "cats-core"                 % "2.1.1",
    "com.amazonaws"      % "aws-java-sdk-s3"           % "1.11.921",
    "com.amazonaws"      % "aws-java-sdk-sqs"          % "1.11.921",
    "com.amazonaws"      % "aws-java-sdk-ec2"          % "1.11.921",
    "org.apache.tika"    % "tika-core"                 % "1.25",
    "org.apache.tika"    % "tika-parsers"              % "1.25",
    "commons-io"         % "commons-io"                % "2.8.0"
  )

  private val test = Seq(
    "uk.gov.hmrc" %% "bootstrap-test-play-28"  % bootstrapVersion % s"$Test,$IntegrationTest",
    "org.mockito" %% "mockito-scala-scalatest" % "1.16.46"        % s"$Test,$IntegrationTest"
  )

  def apply(): Seq[ModuleID] = compile ++ test
}
