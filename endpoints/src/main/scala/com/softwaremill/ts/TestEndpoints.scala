package com.softwaremill.ts

import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.generic.auto._
import io.circe.generic.auto._

object TestEndpoints {
  case class Animal(name: String, height: Double)

  val e0: Endpoint[Unit, Unit, String, Any] = endpoint.get.in("xyz").out(stringBody)
  val e1: Endpoint[(String, Int), Unit, Animal, Any] =
    endpoint.get.in("pets" / path[String]("petId")).in(query[Int]("limit")).out(jsonBody[Animal])
  val e2: Endpoint[String, String, Int, Any] = endpoint.post
    .in("pets" / "add" / path[String]("petId"))
    .errorOut(stringBody)
    .out(header[Int]("X-result"))
}
