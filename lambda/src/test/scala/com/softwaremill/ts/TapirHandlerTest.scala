package com.softwaremill.ts

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

class TapirHandlerTest extends AnyFlatSpec with Matchers {
  val testRequest: String = """{
                              |    "version": "2.0",
                              |    "routeKey": "POST /pets/add/{petId}",
                              |    "rawPath": "/pets/add/xxx",
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
                              |            "path": "/pets/add/xxx",
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
                              |        "petId": "xxx"
                              |    },
                              |    "body": "OTg3",
                              |    "isBase64Encoded": true
                              |}""".stripMargin

  it should "work" in {
    val output = new ByteArrayOutputStream()
    new TapirHandler().handleRequest(new ByteArrayInputStream(testRequest.getBytes("UTF-8")), output, null)
    println(new String(output.toByteArray, "UTF-8"))
  }
}
