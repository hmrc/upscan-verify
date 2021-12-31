import com.typesafe.sbt.packager.Keys.{dockerBaseImage, dockerRepository, dockerUpdateLatest}
import play.sbt.PlayImport.PlayKeys.playDefaultPort
import sbt.Keys._
import sbt._
import uk.gov.hmrc.DefaultBuildSettings.integrationTestSettings
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings

lazy val microservice = Project("upscan-verify", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(
      majorVersion := 1,
      scalaVersion := "2.12.15",
      libraryDependencies ++= AppDependencies(),
      playDefaultPort := 9578,
      scalacOptions += "-target:jvm-1.8"
  )
  .settings(publishingSettings: _*)
  .configs(IntegrationTest)
  .settings(integrationTestSettings(): _*)
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(parallelExecution in Test := false)
  .settings(IntegrationTest / resourceDirectory := baseDirectory.value / "it" / "resources")
  .settings(
    dockerUpdateLatest := true,
    dockerBaseImage := "artefacts.tax.service.gov.uk/hmrc-jre:latest",
    dockerRepository := Some("artefacts.tax.service.gov.uk"))
