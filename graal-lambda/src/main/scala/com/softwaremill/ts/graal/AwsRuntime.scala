package com.softwaremill.ts.graal

import com.softwaremill.ts.TapirHandler
import com.typesafe.scalalogging.StrictLogging
import sttp.client3._

import scala.concurrent.duration.DurationInt

// loosely based on https://github.com/carpe/scalambda/blob/master/native/src/main/scala/io/carpe/scalambda/native/ScalambdaIO.scala
trait AwsRuntime extends TapirHandler with StrictLogging {
  def main(args: Array[String]): Unit = {
    val runtimeApiHost = sys.env("AWS_LAMBDA_RUNTIME_API")
    val nextEventRequest =
      basicRequest.get(uri"http://$runtimeApiHost/2018-06-01/runtime/invocation/next").response(asStringAlways).readTimeout(0.seconds)
    val backend = HttpURLConnectionBackend(SttpBackendOptions.connectionTimeout(0.seconds))

    while (true) {
      logger.trace("Attempting to fetch event")
      val nextEvent = nextEventRequest.send(backend)
      val requestId = nextEvent.header("lambda-runtime-aws-request-id").get

      try {
        val result = handleRequest(nextEvent.body)
        logger.info(s"Request $requestId completed successfully")
        basicRequest.post(uri"http://$runtimeApiHost/2018-06-01/runtime/invocation/$requestId/response").body(result).send(backend)
      } catch {
        case e: Exception =>
          logger.info(s"Request $requestId failed", e)
          basicRequest.post(uri"http://$runtimeApiHost/2018-06-01/runtime/invocation/$requestId/error").body(e.getMessage).send(backend)
      }
    }
  }
}
