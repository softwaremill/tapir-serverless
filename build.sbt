import com.typesafe.sbt.packager.docker.ExecCmd

lazy val commonSettings = commonSmlBuildSettings ++ Seq(
  organization := "com.softwaremill.ts",
  scalaVersion := "2.13.4"
)

lazy val loggerDependencies = Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "ch.qos.logback" % "logback-core" % "1.2.3",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.3" % Test
val amazonSdkVersion = "2.15.77"
val circeVersion = "0.13.0"
val tapirVersion = "0.17.9"

lazy val rootProject = (project in file("."))
  .settings(commonSettings: _*)
  .settings(publishArtifact := false, name := "tapir-serverless")
  .aggregate(endpoints, lambda, createApi)

lazy val endpoints: Project = (project in file("endpoints"))
  .settings(commonSettings: _*)
  .settings(
    name := "endpoints",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion
    ) ++ loggerDependencies
  )

lazy val lambda: Project = (project in file("lambda"))
  .settings(commonSettings: _*)
  .settings(
    name := "lambda",
    libraryDependencies ++= Seq(
      "com.amazonaws" % "aws-lambda-java-runtime-interface-client" % "1.0.0",
      scalaTest
    ),
    packageName in Docker := "tapir-serverless",
    dockerBaseImage := "public.ecr.aws/lambda/java:11",
    daemonUserUid in Docker := None,
    daemonUser in Docker := "daemon",
    dockerUpdateLatest := true,
    dockerCmd := List("com.softwaremill.app.AppHandler::handleRequest"),
    dockerCommands := dockerCommands.value.filterNot {
      case ExecCmd("ENTRYPOINT", _) => true
      case _                        => false
    },
    // https://hub.docker.com/r/amazon/aws-lambda-java
    defaultLinuxInstallLocation in Docker := "/var/task",
    dockerRepository := Some("317104979423.dkr.ecr.eu-central-1.amazonaws.com")
  )
  .dependsOn(endpoints)
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val createApi: Project = (project in file("create-api"))
  .settings(commonSettings: _*)
  .settings(
    name := "create-api",
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "apigatewayv2" % amazonSdkVersion,
      "software.amazon.awssdk" % "lambda" % amazonSdkVersion,
      "software.amazon.awssdk" % "iam" % amazonSdkVersion
    )
  )
  .dependsOn(endpoints)
