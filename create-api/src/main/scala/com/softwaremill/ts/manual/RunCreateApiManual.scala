package com.softwaremill.ts.manual

import com.typesafe.scalalogging.StrictLogging

object RunCreateApiManual extends App with StrictLogging {
  val a = new CreateApi()
  val apiId = a.upsertApi()
  logger.info(s"Got api id: $apiId")

  val stage = "$default"
  a.upsertStage(apiId, stage)

  val targetLambdaFnArn = "arn:aws:lambda:eu-central-1:317104979423:function:tapir-serverless-test"

  import com.softwaremill.ts.TestEndpoints._
  val es = List(e0, e1, e2)
  val nes = es.map(NamedEndpoint(_))
  NamedEndpoint.verifyEndpointPathsUnique(nes)

  val integrationId = a.upsertIntegration(apiId, targetLambdaFnArn)
  a.ensureRoutes(apiId, nes, integrationId)
  a.permitIntegrationToAccessLambda(targetLambdaFnArn, apiId, nes)

  val deploymentStatus = a.deployApi(apiId, stage)
  logger.info(s"Deployment status: $deploymentStatus")
}
