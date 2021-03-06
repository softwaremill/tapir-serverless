import com.typesafe.sbt.packager.docker.{DockerChmodType, DockerPermissionStrategy, ExecCmd}

import scala.sys.process._

lazy val commonSettings = commonSmlBuildSettings ++ Seq(
  organization := "com.softwaremill.ts",
  scalaVersion := "2.13.4"
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.3" % Test
val amazonSdkVersion = "2.15.77"
val circeVersion = "0.13.0"
val tapirVersion = "0.17.11"
val scalaLogging = "3.9.2"
val logback = "1.2.3"
val graalVm = "21.0.0.2"

val deploy = taskKey[Unit]("Builds and uploads a new Docker image, writes the SAM template and deploys it.")

lazy val rootProject = (project in file("."))
  .settings(commonSettings: _*)
  .settings(
    publishArtifact := false,
    name := "tapir-serverless",
    deploy := Def.taskDyn {
      val log = sLog.value
      val appName = name.value.split("-").map(_.capitalize).mkString
      val _ = (publish in Docker in lambda).value
      val imageRepository = (dockerRepository in lambda).value.get
      val imageUri = s"$imageRepository/${(packageName in Docker in lambda).value}:${(version in Docker in lambda).value}"
      val templatePath = (baseDirectory.value / "template.yaml").toString
      val region = "eu-central-1"
      log.info(s"Image uri: $imageUri")

      Def.task {
        val _ = (runMain in createApi in Compile)
          .toTask(s" com.softwaremill.ts.sam.RunSamTemplateInterpreter $appName $imageUri $templatePath")
          .value
        log.info(s"Wrote template to: $templatePath")

        log.info("Running sam ...")
        s"sam deploy --template-file $templatePath --stack-name $appName --image-repository $imageUri --no-confirm-changeset --capabilities CAPABILITY_IAM --region $region".!
        ()
      }
    }.value
  )
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
    )
  )

lazy val lambda: Project = (project in file("lambda"))
  .settings(commonSettings: _*)
  .settings(
    name := "lambda",
    libraryDependencies ++= Seq(
      "com.amazonaws" % "aws-lambda-java-runtime-interface-client" % "1.0.0",
      // using https://github.com/symphoniacloud/lambda-monitoring/tree/master/lambda-logging for fast slf4j-compatible logging
      "io.symphonia" % "lambda-logging" % "1.0.3",
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLogging,
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
    dockerRepository := Some("317104979423.dkr.ecr.eu-central-1.amazonaws.com"),
    version in Docker := git.gitHeadCommit.value.map(_.substring(0, 7)).getOrElse(version.value)
  )
  .dependsOn(endpoints)
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val graalLambda: Project = (project in file("graal-lambda"))
  .settings(commonSettings: _*)
  .settings(
    name := "graal-lambda",
    libraryDependencies ++= Seq(
      "org.graalvm.nativeimage" % "svm" % graalVm % "compile-internal",
      // using https://github.com/symphoniacloud/lambda-monitoring/tree/master/lambda-logging for fast slf4j-compatible logging
      "io.symphonia" % "lambda-logging" % "1.0.3",
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLogging,
      "com.softwaremill.sttp.client3" %% "core" % "3.1.2"
    ),
    //configures sbt-native-packager to build app using dockerized graalvm
    (containerBuildImage in GraalVMNativeImage) := GraalVMNativeImagePlugin
      .generateContainerBuildImage(s"ghcr.io/graalvm/graalvm-ce:java11-$graalVm")
      .value,
    graalVMNativeImageOptions ++= Seq(
      "--static",
      "-H:+ReportExceptionStackTraces",
      "-H:-ThrowUnsafeOffsetErrors",
      "-H:+PrintClassInitialization",
      "--enable-http",
      "--enable-https",
      "--enable-url-protocols=https,http",
      "--initialize-at-build-time",
      "--report-unsupported-elements-at-runtime",
      "--no-fallback",
      "--no-server",
      "--verbose",
      "--allow-incomplete-classpath"
    ),
    dockerBaseImage := "alpine:3.13.1",
//    dockerBaseImage := "openjdk:11.0.10-jdk",
    packageName in Docker := "tapir-serverless-graal",
    dockerUpdateLatest := true,
    dockerRepository := Some("317104979423.dkr.ecr.eu-central-1.amazonaws.com"),
    version in Docker := git.gitHeadCommit.value.map(_.substring(0, 7)).getOrElse(version.value),
    mainClass in Compile := Some("com.softwaremill.ts.graal.App"),
    // lambda requires that any user can run the executable
    dockerChmodType := DockerChmodType.Custom("ugo=rwX"),
    dockerAdditionalPermissions += (DockerChmodType.Custom("ugo=rwx"), "/opt/docker/bin/graal-lambda"),
    mappings in Docker := Seq(
      ((target in GraalVMNativeImage).value / "graal-lambda") -> "/opt/docker/bin/graal-lambda"
    ),
    dockerEntrypoint := Seq("/opt/docker/bin/graal-lambda")
  )
  .dependsOn(lambda)
//  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .enablePlugins(GraalVMNativeImagePlugin, DockerPlugin)

lazy val createApi: Project = (project in file("create-api"))
  .settings(commonSettings: _*)
  .settings(
    name := "create-api",
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "apigatewayv2" % amazonSdkVersion,
      "software.amazon.awssdk" % "lambda" % amazonSdkVersion,
      "software.amazon.awssdk" % "iam" % amazonSdkVersion,
      "io.circe" %% "circe-yaml" % "0.13.1",
      "ch.qos.logback" % "logback-classic" % logback,
      "ch.qos.logback" % "logback-core" % logback,
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLogging
    )
  )
  .dependsOn(endpoints)
