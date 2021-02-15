package com.softwaremill.ts.sam

import com.softwaremill.ts.AnyEndpoint
import sttp.model.Method
import sttp.tapir.EndpointInput
import sttp.tapir.internal._

import scala.collection.immutable.ListMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object SamTemplateInterpreter {
  def apply(
      es: List[AnyEndpoint],
      namePrefix: String,
      imageUri: String,
      timeout: FiniteDuration = 10.seconds,
      memory: Int = 256
  ): SamTemplate = {
    val functionName = namePrefix + "Function"
    val httpApiName = namePrefix + "HttpApi"

    val apiEvents = es.map(endpointNameMethodAndPath).map { case (name, method, path) =>
      name -> FunctionHttpApiEvent(
        FunctionHttpApiEventProperties(s"!Ref $httpApiName", method.map(_.method).getOrElse("ANY"), path, timeout.toMillis)
      )
    }

    SamTemplate(
      Resources = ListMap(
        functionName -> FunctionResource(FunctionProperties(imageUri, timeout.toSeconds, memory, ListMap.from(apiEvents))),
        httpApiName -> HttpResource(HttpProperties("$default"))
      ),
      Outputs = ListMap(
        (namePrefix + "Url") -> Output(
          "Base URL of your endpoints",
          ListMap("Fn::Sub" -> ("https://${" + httpApiName + "}.execute-api.${AWS::Region}.${AWS::URLSuffix}"))
        )
      )
    )
  }

  private def endpointNameMethodAndPath(e: AnyEndpoint): (String, Option[Method], String) = {
    val pathComponents = e.input
      .asVectorOfBasicInputs()
      .collect {
        case EndpointInput.PathCapture(name, _, _) => Left(name)
        case EndpointInput.FixedPath(s, _, _)      => Right(s)
      }
      .foldLeft((Vector.empty[Either[String, String]], 0)) { case ((acc, c), component) =>
        component match {
          case Left(None)    => (acc :+ Left(s"param$c"), c + 1)
          case Left(Some(p)) => (acc :+ Left(p), c)
          case Right(p)      => (acc :+ Right(p), c)
        }
      }
      ._1

    val method = e.httpMethod

    val nameComponents = if (pathComponents.isEmpty) Vector("root") else pathComponents.map(_.fold(identity, identity))
    val name = (method.map(_.method.toLowerCase).getOrElse("any").capitalize +: nameComponents.map(_.toLowerCase.capitalize)).mkString

    val idComponents = pathComponents.map {
      case Left(s)  => s"{$s}"
      case Right(s) => s
    }

    (name, method, "/" + idComponents.mkString("/"))
  }
}
