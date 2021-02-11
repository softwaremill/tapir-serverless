package com.softwaremill.ts

import com.typesafe.scalalogging.StrictLogging
import io.circe.{Decoder, parser}
import io.circe.generic.semiauto.deriveDecoder
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client
import software.amazon.awssdk.services.apigatewayv2.model.{
  CreateApiRequest,
  CreateDeploymentRequest,
  CreateIntegrationRequest,
  CreateRouteRequest,
  CreateStageRequest,
  DeleteRouteRequest,
  GetApisRequest,
  GetIntegrationsRequest,
  GetRoutesRequest,
  GetStagesRequest,
  IntegrationType,
  ProtocolType,
  UpdateRouteRequest
}
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.{AddPermissionRequest, GetPolicyRequest, RemovePermissionRequest}
import sttp.tapir._
import sttp.tapir.internal._

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.CollectionHasAsScala

class CreateApi extends StrictLogging {
  val region: Region = Region.EU_CENTRAL_1

  val client: ApiGatewayV2Client = ApiGatewayV2Client
    .builder()
    .region(region)
    .build()

  val lambdaClient: LambdaClient = LambdaClient
    .builder()
    .region(region)
    .build()

  val iamClient: IamClient = IamClient.builder().region(Region.AWS_GLOBAL).build()

  val apiName = "tapir-serverless-api"

  private def findPaginated[R, I](page: (String, String) => R)(nextToken: R => String, items: R => java.util.List[I])(
      filter: I => Boolean
  ): Option[I] = {
    @tailrec
    def run(lastNextToken: String): Option[I] = {
      val r = page("32", lastNextToken)
      items(r).asScala.find(filter) match {
        case Some(i) => Some(i)
        case None =>
          val t = nextToken(r)
          if (t == null) None else run(t)
      }
    }

    run(null)
  }

  private def findAll[R, I](page: (String, String) => R)(nextToken: R => String, items: R => java.util.List[I]): List[I] = {
    @tailrec
    def run(lastNextToken: String, acc: List[I]): List[I] = {
      val r = page("32", lastNextToken)
      val acc2 = acc ++ items(r).asScala
      val t = nextToken(r)
      if (t == null) acc2 else run(t, acc2)
    }

    run(null, Nil)
  }

  def upsertApi(): String = {
    findPaginated((maxResults, nextToken) => client.getApis(GetApisRequest.builder().maxResults(maxResults).nextToken(nextToken).build()))(
      _.nextToken(),
      _.items()
    )(_.name() == apiName) match {
      case Some(api) => api.apiId()
      case None =>
        logger.info("Creating api")
        client
          .createApi(
            CreateApiRequest
              .builder()
              .name(apiName)
              .protocolType(ProtocolType.HTTP)
              .build()
          )
          .apiId()
    }
  }

  def upsertStage(apiId: String, name: String): Unit = {
    findPaginated((maxResults, nextToken) =>
      client.getStages(GetStagesRequest.builder().apiId(apiId).maxResults(maxResults).nextToken(nextToken).build())
    )(
      _.nextToken(),
      _.items()
    )(_.stageName() == name) match {
      case Some(_) =>
      case None =>
        logger.info("Creating stage")
        client.createStage(CreateStageRequest.builder().apiId(apiId).stageName(name).build())
    }
  }

  def upsertIntegration(apiId: String, lambda: String): String = {
    findPaginated((maxResults, nextToken) =>
      client.getIntegrations(GetIntegrationsRequest.builder().apiId(apiId).maxResults(maxResults).nextToken(nextToken).build())
    )(
      _.nextToken(),
      _.items()
    )(_.integrationUri() == lambda) match {
      case Some(integration) => integration.integrationId()
      case None =>
        logger.info("Creating integration")

        client
          .createIntegration(
            CreateIntegrationRequest
              .builder()
              .apiId(apiId)
              .integrationType(IntegrationType.AWS_PROXY)
              .integrationUri(lambda)
              .payloadFormatVersion("2.0")
              .build()
          )
          .integrationId()
    }
  }

  case class Statement(Sid: String)
  case class Policy(Statement: List[Statement])
  implicit val statementDecoder: Decoder[Statement] = deriveDecoder[Statement]
  implicit val policyDecoder: Decoder[Policy] = deriveDecoder[Policy]

  def permitIntegrationToAccessLambda(functionArn: String, apiId: String, endpoints: List[NamedEndpoint]): Unit = {
    val accountId = iamClient.getUser.user().arn().split(":")(4)
    logger.info(s"Account id: $accountId")

    val policyStr = lambdaClient.getPolicy(GetPolicyRequest.builder().functionName(functionArn).build()).policy()
    val policy = policyDecoder
      .decodeJson(parser.parse(policyStr).getOrElse(throw new IllegalStateException(s"Malformed policy json: $policyStr")))
      .getOrElse(throw new IllegalStateException(s"Cannot parse policy: $policyStr"))

    val statementIdPrefix = apiName + "-"
    val expectedStatementIds = endpoints.map(e => statementIdPrefix + e.operationName)
    val existingStatementIds = policy.Statement.map(_.Sid).filter(_.startsWith(statementIdPrefix))

    val statementIdsToAdd = expectedStatementIds.filterNot(existingStatementIds.contains)
    val statementIdsToRemove = existingStatementIds.filterNot(expectedStatementIds.contains)

    statementIdsToAdd.foreach { statementId =>
      lambdaClient.addPermission(
        AddPermissionRequest
          .builder()
          .functionName(functionArn)
          .statementId(statementId)
          .sourceArn(s"arn:aws:execute-api:${region.id()}:$accountId:$apiId/*/*/*")
          .principal("apigateway.amazonaws.com")
          .action("lambda:InvokeFunction")
          .build()
      )
    }

    statementIdsToRemove.foreach { statementId =>
      lambdaClient.removePermission(RemovePermissionRequest.builder().functionName(functionArn).statementId(statementId).build())
    }
  }

  private def addRoute(apiId: String, key: String, name: String, target: String): String = {
    logger.info(s"Creating route ($name)")
    client
      .createRoute(
        CreateRouteRequest
          .builder()
          .apiId(apiId)
          .target(s"integrations/$target")
          .routeKey(key)
          .operationName(name)
          .build()
      )
      .routeId()
  }

  private def updateRoute(apiId: String, routeId: String, key: String, name: String, target: String): String = {
    logger.info(s"Updating route $routeId ($name)")
    client
      .updateRoute(
        UpdateRouteRequest
          .builder()
          .apiId(apiId)
          .routeId(routeId)
          .target(s"integrations/$target")
          .routeKey(key)
          .operationName(name)
          .build()
      )
      .routeId()
  }

  private def deleteRoute(apiId: String, routeId: String, name: String): Unit = {
    logger.info(s"Deleting route $routeId ($name)")
    client
      .deleteRoute(
        DeleteRouteRequest
          .builder()
          .apiId(apiId)
          .routeId(routeId)
          .build()
      )
  }

  def ensureRoutes(apiId: String, nes: List[NamedEndpoint], target: String): Unit = {
    val allRoutes = findAll((maxResults, nextToken) =>
      client.getRoutes(GetRoutesRequest.builder().apiId(apiId).maxResults(maxResults).nextToken(nextToken).build())
    )(
      _.nextToken(),
      _.items()
    )

    val allRoutesNameToId = allRoutes.map(r => r.operationName() -> r.routeId()).toMap
    val routesToAdd = nes.filter(e => !allRoutesNameToId.contains(e.operationName))
    val routesToUpdate = nes.filter(e => allRoutesNameToId.contains(e.operationName))
    val routesToDelete = allRoutesNameToId.filter { case (operationName, _) => !nes.exists(_.operationName == operationName) }

    routesToAdd.foreach(e => addRoute(apiId, e.operationKey, e.operationName, target))
    routesToUpdate.foreach(e => updateRoute(apiId, allRoutesNameToId(e.operationName), e.operationKey, e.operationName, target))
    routesToDelete.foreach { case (name, routeId) => deleteRoute(apiId, routeId, name) }
  }

  def deployApi(apiId: String, stageName: String): String = {
    client.createDeployment(CreateDeploymentRequest.builder().apiId(apiId).stageName(stageName).build()).deploymentStatusAsString()
  }

}

case class NamedEndpoint(e: Endpoint[_, _, _, _], operationKey: String, operationName: String, id: String)
object NamedEndpoint {
  def apply(e: Endpoint[_, _, _, _]): NamedEndpoint = {
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

    val keyComponents = pathComponents.map {
      case Left(s)  => s"{$s}"
      case Right(s) => s
    }
    val key = s"${method.map(_.method.toUpperCase).getOrElse("ANY")} /${keyComponents.mkString("/")}"

    val nameComponents = if (pathComponents.isEmpty) Vector("root") else pathComponents.map(_.fold(identity, identity))
    val name = (method.map(_.method.toLowerCase).getOrElse("any") +: nameComponents.map(_.toLowerCase.capitalize)).mkString

    val idComponents = pathComponents.map {
      case Left(s)  => s"{}"
      case Right(s) => s
    }

    NamedEndpoint(e, key, name, idComponents.mkString("/"))
  }

  def verifyEndpointPathsUnique(nes: List[NamedEndpoint]): Unit = {
    nes.foldLeft(Map.empty[String, NamedEndpoint]) { case (soFar, ne) =>
      soFar.get(ne.id) match {
        case Some(other) => throw new IllegalArgumentException(s"Endpoints have conflicting paths: ${ne.e.show}, and: ${other.e.show}")
        case None        => soFar + (ne.id -> ne)
      }
    }
  }
}

object Test extends App with StrictLogging {
  val a = new CreateApi()
  val apiId = a.upsertApi()
  logger.info(s"Got api id: $apiId")

  val stage = "$default"
  a.upsertStage(apiId, stage)

  val targetLambdaFnArn = "arn:aws:lambda:eu-central-1:317104979423:function:tapir-serverless-test"

  import TestEndpoints._

  val es = List(e0, e1, e2)
  val nes = es.map(NamedEndpoint(_))
  NamedEndpoint.verifyEndpointPathsUnique(nes)

  val integrationId = a.upsertIntegration(apiId, targetLambdaFnArn)
  a.ensureRoutes(apiId, nes, integrationId)
  a.permitIntegrationToAccessLambda(targetLambdaFnArn, apiId, nes)

  val deploymentStatus = a.deployApi(apiId, stage)
  logger.info(s"Deployment status: $deploymentStatus")
}
