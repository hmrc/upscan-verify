import com.typesafe.sbt.packager.Keys.{dockerBaseImage, dockerRepository, dockerUpdateLatest}
import play.sbt.PlayImport.PlayKeys.playDefaultPort
import sbt.Keys.*
import sbt.*
import uk.gov.hmrc.DefaultBuildSettings

ThisBuild / majorVersion := 1
ThisBuild / scalaVersion := "3.3.4"

lazy val microservice = Project("upscan-verify", file("."))
  .enablePlugins(PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(
      libraryDependencies ++= AppDependencies(),
      playDefaultPort := 9578,
  )
  .settings(scalacOptions ++= Seq(
    "-Wconf:src=routes/.*:s" //silence warnings in generated routes
  , "-Wconf:msg=Flag.*repeatedly:s"
  ))
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(Test / parallelExecution := false)
  .settings(
    dockerUpdateLatest := true,
    dockerBaseImage    := "artefacts.tax.service.gov.uk/hmrc-jre:latest",
    dockerRepository   := Some("artefacts.tax.service.gov.uk")
  )

lazy val it = project
  .enablePlugins(PlayScala)
  .dependsOn(microservice % "test->test") // the "test->test" allows reusing test code and test dependencies
  .settings(DefaultBuildSettings.itSettings())
  .settings(libraryDependencies ++= AppDependencies())
