import sbt._
import uk.gov.hmrc.SbtAutoBuildPlugin
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin
import uk.gov.hmrc.versioning.SbtGitVersioning

object MicroServiceBuild extends Build with MicroService {

  val appName = "upscan-verify"

  override lazy val plugins: Seq[Plugins] = Seq(
    SbtAutoBuildPlugin,
    SbtGitVersioning,
    SbtDistributablesPlugin
  )

  override lazy val appDependencies: Seq[ModuleID] = AppDependencies()

}

private object AppDependencies {
  import play.core.PlayVersion

  val compile = Seq(
    "uk.gov.hmrc"       %% "bootstrap-play-25" % "1.4.0",
    "org.typelevel"     %% "cats-core"         % "1.0.1",
    "com.amazonaws"     % "aws-java-sdk-s3"    % "1.11.261",
    "com.amazonaws"     % "aws-java-sdk-sqs"   % "1.11.285",
    "com.amazonaws"     % "aws-java-sdk-ec2"   % "1.11.285",
    "com.typesafe.akka" %% "akka-stream"       % "2.5.6",
    "uk.gov.hmrc"       %% "clamav-client"     % "6.0.0"
  )

  trait TestDependencies {
    lazy val scope: String       = "test"
    lazy val test: Seq[ModuleID] = ???
  }

  private def commonTestDependencies(scope: String) = Seq(
    "uk.gov.hmrc"            %% "hmrctest"                    % "3.0.0"             % scope,
    "uk.gov.hmrc"            %% "http-verbs-test"             % "1.1.0"             % scope,
    "org.scalatest"          %% "scalatest"                   % "2.2.6"             % scope,
    "org.pegdown"            % "pegdown"                      % "1.6.0"             % scope,
    "com.typesafe.play"      %% "play-test"                   % PlayVersion.current % scope,
    "org.mockito"            % "mockito-core"                 % "2.6.2"             % scope,
    "org.scalamock"          %% "scalamock-scalatest-support" % "3.5.0"             % scope,
    "org.scalatestplus.play" %% "scalatestplus-play"          % "2.0.0"             % scope,
    "com.typesafe.play"      %% "play-ws"                     % "2.5.6"             % scope,
    "commons-io"             % "commons-io"                   % "2.6"               % scope
  )

  object Test {
    def apply() =
      new TestDependencies {
        override lazy val test = commonTestDependencies(scope)
      }.test
  }

  object IntegrationTest {
    def apply() =
      new TestDependencies {

        override lazy val scope: String = "it"

        override lazy val test = commonTestDependencies(scope)
      }.test
  }

  def apply() = compile ++ Test() ++ IntegrationTest()
}
