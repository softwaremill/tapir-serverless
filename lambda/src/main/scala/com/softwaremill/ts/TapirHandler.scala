package com.softwaremill.ts

import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}
import com.softwaremill.ts.TestEndpoints.Animal
import com.softwaremill.ts.model.{AwsRequest, AwsResponse}
import io.circe.Printer
import sttp.model.{Method, QueryParams, StatusCode, Uri}
import sttp.monad.MonadError
import sttp.tapir.internal.{NoStreams, ParamsAsAny}
import sttp.tapir.model.{ConnectionInfo, ServerRequest}
import sttp.tapir.server.internal.{
  DecodeInputs,
  DecodeInputsContext,
  DecodeInputsResult,
  EncodeOutputBody,
  EncodeOutputs,
  InputValues,
  InputValuesResult,
  OutputValues
}
import sttp.tapir.server.{DecodeFailureContext, DecodeFailureHandler, DecodeFailureHandling, ServerDefaults, ServerEndpoint}
import sttp.tapir.{CodecFormat, DecodeResult, EndpointIO, EndpointInput, EndpointOutput, RawBodyType, WebSocketBodyOutput}

import java.io.{BufferedWriter, ByteArrayInputStream, InputStream, OutputStream, OutputStreamWriter}
import java.net.{InetSocketAddress, URI}
import java.nio.ByteBuffer
import java.nio.charset.{Charset, StandardCharsets}
import java.util.Base64
import scala.annotation.tailrec
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._

class TapirHandler extends RequestStreamHandler {

  private val se0 = TestEndpoints.e0.serverLogic[Identity](_ => Right("ok"))
  private val se1 = TestEndpoints.e1.serverLogic[Identity] { case (a, b) => Right(Animal(a, b.toDouble)) }
  private val se2 = TestEndpoints.e2.serverLogic[Identity](x => if (x == "error") Left("błąd") else Right(x.length))

  override def handleRequest(input: InputStream, output: OutputStream, context: Context): Unit = {
    val json = new String(input.readAllBytes(), StandardCharsets.UTF_8)

    val resp = decode[AwsRequest](json) match {
      case Left(error) => AwsResponse(Nil, isBase64Encoded = false, StatusCode.BadRequest.code, Map.empty, error.getMessage)
      case Right(req)  => AwsServerInterpreter(req, List(se0, se1, se2))
    }

    val writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))
    writer.write(Printer.noSpaces.print(resp.asJson))
    writer.flush()
  }
}

case class AwsServerOptions(decodeFailureHandler: DecodeFailureHandler)
object AwsServerOptions {
  implicit lazy val default: AwsServerOptions = AwsServerOptions(ServerDefaults.decodeFailureHandler)
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

object AwsServerInterpreter {
  def apply[I, E, O](req: AwsRequest, serverEndpoints: List[ServerEndpoint[_, _, _, Any, Identity]]): AwsResponse = {
    @tailrec
    def run(ses: List[ServerEndpoint[_, _, _, Any, Identity]]): Option[AwsResponse] = {
      ses match {
        case Nil => None
        case head :: tail =>
          apply(req, head) match {
            case None       => run(tail)
            case Some(resp) => Some(resp)
          }
      }
    }

    try run(serverEndpoints) match {
      case Some(resp) => resp
      case None       => AwsResponse(Nil, isBase64Encoded = false, StatusCode.NotFound.code, Map.empty, "")
    } catch {
      case e: Exception =>
        AwsResponse(Nil, isBase64Encoded = false, StatusCode.InternalServerError.code, Map.empty, e.getMessage)
    }
  }

  def apply[I, E, O](req: AwsRequest, serverEndpoint: ServerEndpoint[I, E, O, Any, Identity]): Option[AwsResponse] = {
    val ctx = AwsDecodeInputsContext(req)
    decodeBody(req, DecodeInputs(serverEndpoint.endpoint.input, ctx)) match {
      case values: DecodeInputsResult.Values =>
        InputValues(serverEndpoint.input, values) match {
          case InputValuesResult.Value(params, _) =>
            val input = params.asAny.asInstanceOf[I]
            serverEndpoint.logic(IdentityMonadError)(input) match {
              case Left(e)  => Some(respondWith(serverEndpoint.errorOutput, e, StatusCode.BadRequest))
              case Right(o) => Some(respondWith(serverEndpoint.output, o, StatusCode.Ok))
            }
          case InputValuesResult.Failure(input, failure) => handleDecodeFailure(serverEndpoint.endpoint, input, failure)
        }

      case DecodeInputsResult.Failure(input, failure) => handleDecodeFailure(serverEndpoint.endpoint, input, failure)
    }
  }

  private def handleDecodeFailure(e: AnyEndpoint, input: EndpointInput[_], failure: DecodeResult.Failure)(implicit
      options: AwsServerOptions
  ): Option[AwsResponse] = {
    options.decodeFailureHandler.apply(DecodeFailureContext(input, failure, e)) match {
      case DecodeFailureHandling.NoMatch                            => None
      case DecodeFailureHandling.RespondWithResponse(output, value) => Some(respondWith(output, value, StatusCode.BadRequest))
    }
  }

  private def respondWith[O](output: EndpointOutput[O], value: O, defaultStatusCode: StatusCode): AwsResponse = {
    val outputValues = encodeOutputs(output, ParamsAsAny(value), OutputValues.empty)
    val body = outputValues.body.map(_.left.getOrElse("")).getOrElse("")
    AwsResponse(
      cookies = Nil,
      isBase64Encoded = body.nonEmpty,
      statusCode = outputValues.statusCode.getOrElse(defaultStatusCode).code,
      headers = outputValues.headers.toMap,
      body = body
    )
  }

  private def decodeBody(req: AwsRequest, result: DecodeInputsResult): DecodeInputsResult = {
    result match {
      case values: DecodeInputsResult.Values =>
        values.bodyInput match {
          case Some(bodyInput @ EndpointIO.Body(bodyType, codec, _)) =>
            val body = stringToRawValue(req.body, req.isBase64Encoded, bodyType)
            codec.decode(body) match {
              case DecodeResult.Value(bodyV)     => values.setBodyInputValue(bodyV)
              case failure: DecodeResult.Failure => DecodeInputsResult.Failure(bodyInput, failure): DecodeInputsResult
            }

          case None => values
        }
      case failure: DecodeInputsResult.Failure => failure
    }
  }

  private def stringToRawValue[R](
      body: String,
      isBase64Encoded: Boolean,
      bodyType: RawBodyType[R]
  ): R = {
    val decoded = if (isBase64Encoded) Left(Base64.getDecoder.decode(body)) else Right(body)
    def asByteArray = decoded.fold(identity[Array[Byte]], _.getBytes())
    bodyType match {
      case RawBodyType.StringBody(charset) => decoded.fold(new String(_, charset), identity[String])
      case RawBodyType.ByteArrayBody       => asByteArray
      case RawBodyType.ByteBufferBody      => ByteBuffer.wrap(asByteArray)
      case RawBodyType.InputStreamBody     => new ByteArrayInputStream(asByteArray)
      case RawBodyType.FileBody            => throw new UnsupportedOperationException
      case _: RawBodyType.MultipartBody    => throw new UnsupportedOperationException
    }
  }

  private val encodeOutputs: EncodeOutputs[String, Nothing, Nothing] =
    new EncodeOutputs[String, Nothing, Nothing](new EncodeOutputBody[String, Nothing, Nothing] {
      override val streams: NoStreams = NoStreams
      override def rawValueToBody[R](v: R, format: CodecFormat, bodyType: RawBodyType[R]): String =
        rawValueToBase64Encoded(bodyType.asInstanceOf[RawBodyType[Any]], v)
      override def streamValueToBody(v: Nothing, format: CodecFormat, charset: Option[Charset]): String =
        v
      override def webSocketPipeToBody[REQ, RESP](
          pipe: Nothing,
          o: WebSocketBodyOutput[streams.Pipe[REQ, RESP], REQ, RESP, _, Nothing]
      ): Nothing = pipe
    })

  private def rawValueToBase64Encoded[R](bodyType: RawBodyType[R], r: R): String = {
    def safeRead(byteBuffer: ByteBuffer): Array[Byte] = {
      if (byteBuffer.hasArray) {
        byteBuffer.array()
      } else {
        val array = new Array[Byte](byteBuffer.remaining())
        byteBuffer.get(array)
        array
      }
    }

    bodyType match {
      case RawBodyType.StringBody(charset) =>
        val str = r.asInstanceOf[String]
        Base64.getEncoder.encodeToString(str.getBytes(charset))

      case RawBodyType.ByteArrayBody =>
        val bytes = r.asInstanceOf[Array[Byte]]
        Base64.getEncoder.encodeToString(bytes)

      case RawBodyType.ByteBufferBody =>
        val byteBuffer = r.asInstanceOf[ByteBuffer]
        Base64.getEncoder.encodeToString(safeRead(byteBuffer))

      case RawBodyType.InputStreamBody =>
        val stream = r.asInstanceOf[InputStream]
        Base64.getEncoder.encodeToString(stream.readAllBytes())

      case RawBodyType.FileBody => throw new UnsupportedOperationException

      case _: RawBodyType.MultipartBody => throw new UnsupportedOperationException
    }
  }
}

object IdentityMonadError extends MonadError[Identity] {
  override def unit[T](t: T): Identity[T] = t
  override def map[T, T2](fa: Identity[T])(f: T => T2): Identity[T2] = f(fa)
  override def flatMap[T, T2](fa: Identity[T])(f: T => Identity[T2]): Identity[T2] = f(fa)
  override def error[T](t: Throwable): Identity[T] = throw t
  override protected def handleWrappedError[T](rt: Identity[T])(h: PartialFunction[Throwable, Identity[T]]): Identity[T] = rt
  override def ensure[T](f: Identity[T], e: => Identity[Unit]): Identity[T] = try f
  finally e
}
