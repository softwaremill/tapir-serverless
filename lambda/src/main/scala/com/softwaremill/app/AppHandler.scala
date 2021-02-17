package com.softwaremill.app

import com.softwaremill.ts.{Identity, TapirHandler, TestEndpoints}
import com.softwaremill.ts.TestEndpoints.Animal
import sttp.tapir.server.ServerEndpoint

class AppHandler extends TapirHandler {
  private val serverEndpoint1 = TestEndpoints.endpoint1.serverLogic[Identity](_ => Right("ok"))
  private val serverEndpoint2 = TestEndpoints.endpoint2.serverLogic[Identity] { case (id, name) => Right(Animal(id, name)) }
  private val serverEndpoint3 = TestEndpoints.endpoint3.serverLogic[Identity] { case (id, authToken) =>
    if (authToken.token != "secret") Left("unauthorized") else Right(id.toString.length)
  }

  override val endpoints: List[ServerEndpoint[_, _, _, Any, Identity]] = List(serverEndpoint1, serverEndpoint2, serverEndpoint3)
}
