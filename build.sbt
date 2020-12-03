import com.typesafe.sbt.packager.Keys.{dockerBaseImage, dockerRepository, dockerUpdateLatest}
import play.sbt.PlayImport.PlayKeys.playDefaultPort
import sbt.Keys._
import sbt._
import scoverage.ScoverageKeys
import uk.gov.hmrc.DefaultBuildSettings.integrationTestSettings
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin

val appName = "upscan-verify"

lazy val scoverageSettings = Seq(
    // Semicolon-separated list of regexs matching classes to exclude
    ScoverageKeys.coverageExcludedPackages := "<empty>;Reverse.*;.*AuthService.*;models/.data/..*;view.*",
    ScoverageKeys.coverageExcludedFiles :=
      ".*/frontendGlobal.*;.*/frontendAppConfig.*;.*/frontendWiring.*;.*/views/.*_template.*;.*/govuk_wrapper.*;.*/routes_routing.*;.*/BuildInfo.*",
    // Minimum is deliberately low to avoid failures initially - please increase as we add more coverage
    ScoverageKeys.coverageMinimum := 25,
    ScoverageKeys.coverageFailOnMinimum := false,
    ScoverageKeys.coverageHighlighting := true
  )

lazy val microservice = Project(appName, file("."))
  .enablePlugins(Seq(play.sbt.PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin, SbtArtifactory): _*)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(scoverageSettings: _*)
  .settings(majorVersion := 1)
  .settings(SbtDistributablesPlugin.publishingSettings: _*)
  .settings(playDefaultPort := 9578)
  .settings(libraryDependencies ++= AppDependencies())
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(scalacOptions += "-target:jvm-1.8")
  .settings(scalaVersion := "2.12.12")
  .settings(parallelExecution in Test := false)
  .configs(IntegrationTest)
  .settings(integrationTestSettings(): _*)
  .settings(IntegrationTest / resourceDirectory := baseDirectory.value / "it" / "resources")
  .settings(Seq(
    dockerUpdateLatest := true,
    dockerBaseImage := "artefacts.tax.service.gov.uk/hmrc-jre:latest",
    dockerRepository := Some("artefacts.tax.service.gov.uk")))
