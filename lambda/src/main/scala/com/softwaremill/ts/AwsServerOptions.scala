package com.softwaremill.ts

import sttp.tapir.server.{DecodeFailureHandler, ServerDefaults}

case class AwsServerOptions(decodeFailureHandler: DecodeFailureHandler)
object AwsServerOptions {
  implicit lazy val default: AwsServerOptions = AwsServerOptions(ServerDefaults.decodeFailureHandler)
}
