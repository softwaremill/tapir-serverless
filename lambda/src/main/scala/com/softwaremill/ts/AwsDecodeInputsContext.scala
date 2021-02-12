package com.softwaremill.ts

import sttp.model.{Method, QueryParams, Uri}
import sttp.tapir.model.{ConnectionInfo, ServerRequest}
import sttp.tapir.server.internal.DecodeInputsContext

import java.net.{InetSocketAddress, URI}

case class AwsDecodeInputsContext(serverRequest: AwsServerRequest, path: List[String]) extends DecodeInputsContext {
  override def method: Method = serverRequest.method
  override def nextPathSegment: (Option[String], DecodeInputsContext) = path match {
    case head :: tail => (Some(head), AwsDecodeInputsContext(serverRequest, tail))
    case Nil          => (None, this)
  }
  override def header(name: String): List[String] = serverRequest.header(name).toList
  override def headers: Seq[(String, String)] = serverRequest.headers
  override def queryParameter(name: String): Seq[String] = serverRequest.sttpUri.params.getMulti(name).toList.flatten
  override def queryParameters: QueryParams = serverRequest.sttpUri.params
  override def bodyStream: Any = throw new UnsupportedOperationException
}
object AwsDecodeInputsContext {
  def apply(req: AwsRequest): AwsDecodeInputsContext = {
    val serverRequest = new AwsServerRequest(req)
    AwsDecodeInputsContext(serverRequest, serverRequest.sttpUri.path.toList)
  }
}

class AwsServerRequest(req: AwsRequest) extends ServerRequest {
  override val method: Method = Method.unsafeApply(req.requestContext.http.method)
  override val protocol: String = req.headers.getOrElse("x-forwarded-proto", "http")
  val sttpUri: Uri = {
    val queryString = if (req.rawQueryString.nonEmpty) "?" + req.rawQueryString else ""
    Uri.unsafeParse(s"$protocol://${req.requestContext.domainName}${req.rawPath}$queryString")
  }
  override def uri: URI = sttpUri.toJavaUri
  override def connectionInfo: ConnectionInfo =
    ConnectionInfo(None, Some(InetSocketAddress.createUnresolved(req.requestContext.http.sourceIp, 80)), None)
  override val headers: Seq[(String, String)] = req.headers.toList
  override def header(name: String): Option[String] = req.headers.get(name)
  override val underlying: Any = req
}
