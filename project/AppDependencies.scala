import sbt._

object AppDependencies {

  private val bootstrapVersion = "9.5.0"

  private val compile = Seq(
    "uk.gov.hmrc"       %% "bootstrap-backend-play-30"     % bootstrapVersion,
    "org.typelevel"     %% "cats-core"                     % "2.12.0",
    "com.amazonaws"      % "aws-java-sdk-s3"               % "1.12.606",
    "com.amazonaws"      % "aws-java-sdk-sqs"              % "1.12.606",
    "com.amazonaws"      % "aws-java-sdk-ec2"              % "1.12.606",
    "org.apache.tika"    % "tika-core"                     % "2.9.1",
    "org.apache.tika"    % "tika-parsers-standard-package" % "2.9.1"
      excludeAll(ExclusionRule(organization = "com.fasterxml.jackson.core")),
    "commons-io"         % "commons-io"                    % "2.15.1"
  )

  private val test = Seq(
    "uk.gov.hmrc" %% "bootstrap-test-play-30"  % bootstrapVersion % Test,
    "org.mockito" %% "mockito-scala-scalatest" % "1.17.29"        % Test
  )

  def apply(): Seq[ModuleID] = compile ++ test
}
