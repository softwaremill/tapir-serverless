package com.softwaremill.ts

import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}
import io.circe.Printer
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import sttp.model.StatusCode
import sttp.tapir.server.ServerEndpoint

import java.io.{BufferedWriter, InputStream, OutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets

trait TapirHandler extends RequestStreamHandler {
  def endpoints: List[ServerEndpoint[_, _, _, Any, Identity]]

  override def handleRequest(input: InputStream, output: OutputStream, context: Context): Unit = {
    val json = new String(input.readAllBytes(), StandardCharsets.UTF_8)
    val writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))
    writer.write(handleRequest(json))
    writer.flush()
  }

  def handleRequest(input: String): String = {
    val resp = decode[AwsRequest](input) match {
      case Left(error) => AwsResponse(Nil, isBase64Encoded = false, StatusCode.BadRequest.code, Map.empty, error.getMessage)
      case Right(req)  => AwsServerInterpreter(req, endpoints)
    }

    Printer.noSpaces.print(resp.asJson)
  }
}
