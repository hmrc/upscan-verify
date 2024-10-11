import sbt._

object AppDependencies {

  private val bootstrapVersion = "9.5.0"

  private val compile = Seq(
    "uk.gov.hmrc"       %% "bootstrap-backend-play-30"     % bootstrapVersion,
    "org.typelevel"     %% "cats-core"                     % "2.12.0",
    //"software.amazon.awssdk"  %  "sqs"                       % "2.28.19",
    "software.amazon.awssdk"  %  "s3"                       % "2.28.19",
    //"com.amazonaws"      % "aws-java-sdk-s3"               % "1.12.606",
    "com.amazonaws"      % "aws-java-sdk-sqs"              % "1.12.606",
    "org.apache.tika"    % "tika-core"                     % "2.9.2",
    "org.apache.tika"    % "tika-parsers-standard-package" % "2.9.2"
      excludeAll(ExclusionRule(organization = "com.fasterxml.jackson.core")),
    "commons-io"         % "commons-io"                    % "2.15.1"
  )

  private val test = Seq(
    "uk.gov.hmrc" %% "bootstrap-test-play-30"  % bootstrapVersion % Test
  )

  def apply(): Seq[ModuleID] = compile ++ test
}
