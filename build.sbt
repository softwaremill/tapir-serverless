import com.typesafe.sbt.packager.docker.ExecCmd
import scala.sys.process._

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
val tapirVersion = "0.17.11"

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
    dockerRepository := Some("317104979423.dkr.ecr.eu-central-1.amazonaws.com"),
    version in Docker := git.gitHeadCommit.value.map(_.substring(0, 7)).getOrElse(version.value)
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
      "software.amazon.awssdk" % "iam" % amazonSdkVersion,
      "io.circe" %% "circe-yaml" % "0.13.1"
    )
  )
  .dependsOn(endpoints)
