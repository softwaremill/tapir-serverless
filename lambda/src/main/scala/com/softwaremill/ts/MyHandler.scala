package com.softwaremill.ts

import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}

import java.io.{BufferedWriter, InputStream, OutputStream, OutputStreamWriter, PrintWriter}
import scala.jdk.CollectionConverters.MapHasAsScala

class MyHandler extends RequestStreamHandler {
  var local = 0

  override def handleRequest(input: InputStream, output: OutputStream, context: Context): Unit = {
    val logger = context.getLogger
    // log execution details
    logger.log("ENVIRONMENT VARIABLES: " + System.getenv().asScala + "\n")
    logger.log("CONTEXT: " + context + "\n")
    // process event
    val json = new String(input.readAllBytes(), "UTF-8")
    logger.log("JSON: " + json + "\n")

//    logger.log("EVENT CLASS: " + event.getClass.getName + "\n")
//    logger.log("AAA1: " + event.asInstanceOf[java.util.LinkedHashMap[String, Any]].get("headers") + "\n")
//    logger.log("AAA2: " + clsOrNull(event.asInstanceOf[java.util.LinkedHashMap[String, Any]].get("headers")) + "\n")
//    logger.log("BBB1: " + event.asInstanceOf[java.util.LinkedHashMap[String, Any]].get("requestContext") + "\n")
//    logger.log("BBB2: " + clsOrNull(event.asInstanceOf[java.util.LinkedHashMap[String, Any]].get("requestContext")) + "\n")
    local += 1
    MyHandler.global += 1
    val result = s""""200 OK Local: $local global: ${MyHandler.global}""""

    val writer = new BufferedWriter(new OutputStreamWriter(output, "UTF-8"))
    writer.write(result)
    writer.flush()
  }

  def clsOrNull(x: Any): String = if (x == null) "null" else x.getClass.getName
}

object MyHandler {
  var global = 0
}
