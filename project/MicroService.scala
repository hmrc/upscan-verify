import play.routes.compiler.StaticRoutesGenerator
import play.sbt.PlayImport.PlayKeys
import play.sbt.routes.RoutesKeys.routesGenerator
import sbt.Keys._
import sbt.Tests.{Group, SubProcess}
import sbt._
import uk.gov.hmrc.SbtArtifactory.autoImport.makePublicallyAvailableOnBintray
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin
import uk.gov.hmrc.versioning.SbtGitVersioning
import uk.gov.hmrc.versioning.SbtGitVersioning.autoImport.majorVersion

trait MicroService {

  import uk.gov.hmrc._
  import DefaultBuildSettings.{addTestReportOption, defaultSettings, scalaSettings, targetJvm}
  import scoverage.ScoverageKeys

  val appName: String

  lazy val appDependencies: Seq[ModuleID] = ???
  lazy val plugins: Seq[Plugins] = Seq(
    play.sbt.PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin, SbtArtifactory
  )
  lazy val playSettings: Seq[Setting[_]]  = Seq.empty

  lazy val scoverageSettings = {
    Seq(
      // Semicolon-separated list of regexs matching classes to exclude
      ScoverageKeys.coverageExcludedPackages := "<empty>;Reverse.*;.*AuthService.*;models/.data/..*;view.*",
      ScoverageKeys.coverageExcludedFiles :=
        ".*/frontendGlobal.*;.*/frontendAppConfig.*;.*/frontendWiring.*;.*/views/.*_template.*;.*/govuk_wrapper.*;.*/routes_routing.*;.*/BuildInfo.*",
      // Minimum is deliberately low to avoid failures initially - please increase as we add more coverage
      ScoverageKeys.coverageMinimum := 25,
      ScoverageKeys.coverageFailOnMinimum := false,
      ScoverageKeys.coverageHighlighting := true,
      parallelExecution in Test := false
    )
  }

  lazy val microservice = Project(appName, file("."))
    .enablePlugins(plugins: _*)
    .settings(PlayKeys.playDefaultPort := 9578)
    .settings(playSettings: _*)
    .settings(scalaSettings: _*)
    .settings(defaultSettings(): _*)
    .settings(
      targetJvm := "jvm-1.8",
      libraryDependencies ++= appDependencies,
      parallelExecution in Test := false,
      fork in Test := false,
      retrieveManaged := true,
      routesGenerator := StaticRoutesGenerator
    )
    .settings(majorVersion := 1, makePublicallyAvailableOnBintray := true)
    .configs(IntegrationTest)
    .settings(inConfig(IntegrationTest)(Defaults.itSettings): _*)
    .settings(SbtDistributablesPlugin.publishingSettings: _*)
    .settings(
      Keys.fork in IntegrationTest := false,
      unmanagedSourceDirectories in IntegrationTest <<= (baseDirectory in IntegrationTest)(base => Seq(base / "it")),
      addTestReportOption(IntegrationTest, "int-test-reports"),
      testGrouping in IntegrationTest := TestPhases.oneForkedJvmPerTest((definedTests in IntegrationTest).value),
      parallelExecution in IntegrationTest := false
    )
    .disablePlugins(sbt.plugins.JUnitXmlReportPlugin)
    .settings(resolvers ++= Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.jcenterRepo
    ))
}

private object TestPhases {

  def oneForkedJvmPerTest(tests: Seq[TestDefinition]) =
    tests map { test =>
      new Group(test.name, Seq(test), SubProcess(ForkOptions(runJVMOptions = Seq("-Dtest.name=" + test.name))))
    }
}
