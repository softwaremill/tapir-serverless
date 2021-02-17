package com.softwaremill.ts

import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.generic.auto._
import io.circe.generic.auto._

object TestEndpoints {
  case class AuthToken(token: String)
  case class Animal(id: Int, name: String)

  val endpoint1: Endpoint[Unit, Unit, String, Any] =
    endpoint.get.in("hello").out(stringBody)

  val endpoint2: Endpoint[(Int, String), Unit, Animal, Any] =
    endpoint.get.in("pets" / path[Int]("petId")).in(query[String]("name")).out(jsonBody[Animal])

  val endpoint3: Endpoint[(Int, AuthToken), String, Int, Any] =
    endpoint.post
      .in("pets" / "add" / path[Int]("petId"))
      .in(auth.bearer[String]().mapTo(AuthToken))
      .errorOut(stringBody)
      .out(header[Int]("X-affected-rows"))
}
