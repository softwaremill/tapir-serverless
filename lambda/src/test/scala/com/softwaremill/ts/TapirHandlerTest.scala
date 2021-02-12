package com.softwaremill.ts

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir._
import sttp.tapir.server.ServerEndpoint

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

class TapirHandlerTest extends AnyFlatSpec with Matchers {
  val testRequest: String = """{
                              |    "version": "2.0",
                              |    "routeKey": "POST /pets/add/{petId}",
                              |    "rawPath": "/pets/add/cat01",
                              |    "rawQueryString": "a=123",
                              |    "headers": {
                              |        "accept": "*/*",
                              |        "content-length": "3",
                              |        "content-type": "application/x-www-form-urlencoded",
                              |        "host": "9abc9.execute-api.eu-central-1.amazonaws.com",
                              |        "user-agent": "curl/7.64.1",
                              |        "x-amzn-trace-id": "Root=1-60250d19-7182a3ff0e9dffb334e2bf74",
                              |        "x-forwarded-for": "78.11.177.136",
                              |        "x-forwarded-port": "443",
                              |        "x-forwarded-proto": "https"
                              |    },
                              |    "queryStringParameters": {
                              |        "a": "123"
                              |    },
                              |    "requestContext": {
                              |        "accountId": "1234567890",
                              |        "apiId": "9abc9",
                              |        "domainName": "9abc9.execute-api.eu-central-1.amazonaws.com",
                              |        "domainPrefix": "9abc9",
                              |        "http": {
                              |            "method": "POST",
                              |            "path": "/pets/add/cat01",
                              |            "protocol": "HTTP/1.1",
                              |            "sourceIp": "78.11.177.136",
                              |            "userAgent": "curl/7.64.1"
                              |        },
                              |        "requestId": "ak78CiA8FiAEPWQ=",
                              |        "routeKey": "POST /pets/add/{petId}",
                              |        "stage": "$default",
                              |        "time": "11/Feb/2021:10:55:21 +0000",
                              |        "timeEpoch": 1613040921706
                              |    },
                              |    "pathParameters": {
                              |        "petId": "cat01"
                              |    },
                              |    "body": "OTg3",
                              |    "isBase64Encoded": true
                              |}""".stripMargin

  it should "work" in {
    val output = new ByteArrayOutputStream()
    new TapirHandler() {
      override def endpoints: List[ServerEndpoint[_, _, _, Any, Identity]] = List(
        endpoint.get
          .in("pets" / path[String]("petId"))
          .in(query[Int]("limit"))
          .out(stringBody)
          .serverLogic[Identity](_ => Right("ok1")),
        endpoint.post
          .in("pets" / "add" / path[String]("petId"))
          .errorOut(stringBody)
          .out(header[Int]("X-result"))
          .serverLogic[Identity](id => Right(id.length))
      )
    }.handleRequest(new ByteArrayInputStream(testRequest.getBytes("UTF-8")), output, null)

    val response = new String(output.toByteArray, "UTF-8")

    response shouldBe """{"cookies":[],"isBase64Encoded":false,"statusCode":200,"headers":{"X-result":"5"},"body":""}"""
  }
}
