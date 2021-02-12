package com.softwaremill.app

import com.softwaremill.ts.{Identity, TapirHandler, TestEndpoints}
import com.softwaremill.ts.TestEndpoints.Animal
import sttp.tapir.server.ServerEndpoint

class AppHandler extends TapirHandler {
  private val se0 = TestEndpoints.e0.serverLogic[Identity](_ => Right("ok"))
  private val se1 = TestEndpoints.e1.serverLogic[Identity] { case (a, b) => Right(Animal(a, b.toDouble)) }
  private val se2 = TestEndpoints.e2.serverLogic[Identity](x => if (x == "error") Left("błąd") else Right(x.length))

  override val endpoints: List[ServerEndpoint[_, _, _, Any, Identity]] = List(se0, se1, se2)
}
