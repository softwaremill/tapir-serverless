package com.softwaremill.ts

import sttp.model.StatusCode
import sttp.monad.MonadError
import sttp.tapir.internal.{NoStreams, ParamsAsAny}
import sttp.tapir.server.internal.{
  DecodeBody,
  DecodeInputs,
  DecodeInputsResult,
  EncodeOutputBody,
  EncodeOutputs,
  InputValues,
  InputValuesResult,
  OutputValues
}
import sttp.tapir.server.{DecodeFailureContext, DecodeFailureHandling, ServerEndpoint}
import sttp.tapir.{CodecFormat, DecodeResult, EndpointIO, EndpointInput, EndpointOutput, RawBodyType, WebSocketBodyOutput}

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.Base64
import scala.annotation.tailrec

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

  private def apply[I, E, O](req: AwsRequest, serverEndpoint: ServerEndpoint[I, E, O, Any, Identity]): Option[AwsResponse] = {
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

  private val decodeBody = new DecodeBody[AwsRequest, Identity]()(IdentityMonadError) {
    override def rawBody[R](request: AwsRequest, body: EndpointIO.Body[R, _]): Identity[R] =
      stringToRawValue(request.body.getOrElse(""), request.isBase64Encoded, body.bodyType)
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

  private object IdentityMonadError extends MonadError[Identity] {
    override def unit[T](t: T): Identity[T] = t
    override def map[T, T2](fa: Identity[T])(f: T => T2): Identity[T2] = f(fa)
    override def flatMap[T, T2](fa: Identity[T])(f: T => Identity[T2]): Identity[T2] = f(fa)
    override def error[T](t: Throwable): Identity[T] = throw t
    override protected def handleWrappedError[T](rt: Identity[T])(h: PartialFunction[Throwable, Identity[T]]): Identity[T] = rt
    override def ensure[T](f: Identity[T], e: => Identity[Unit]): Identity[T] = try f
    finally e
  }
}
