package ch.linkyard.mcp.jsonrpc2

import ch.linkyard.mcp.jsonrpc2.JsonRpc.Response.Success
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

object JsonRpc:
  sealed trait Message

  case class Request(id: Id, method: String, params: Option[JsonObject]) extends Message
  object Request:
    given Encoder[Request] = Message.given_Encoder_Message.contramap(identity)
    given Decoder[Request] = Message.given_Decoder_Message.emap(_ match
      case r: Request => Right(r)
      case other      => Left(s"Not a JSONRPC Request"))

  sealed trait Response extends Message:
    def id: Id
  object Response:
    case class Success(id: Id, result: JsonObject) extends Response
    case class Error(id: Id, code: ErrorCode, message: String, data: Option[Json]) extends Response

    given Encoder[Response] = Message.given_Encoder_Message.contramap(identity)
    given Decoder[Response] = Message.given_Decoder_Message.emap(_ match
      case s: Success => Right(s)
      case e: Error   => Right(e)
      case other      => Left(s"Not a JSONRPC Response"))
    given Encoder[Success] = Message.given_Encoder_Message.contramap(identity)
    given Decoder[Success] = Message.given_Decoder_Message.emap(_ match
      case s: Success => Right(s)
      case other      => Left(s"Not a JSONRPC Success Response"))
    given Encoder[Error] = Message.given_Encoder_Message.contramap(identity)
    given Decoder[Error] = Message.given_Decoder_Message.emap(_ match
      case e: Error => Right(e)
      case other    => Left(s"Not a JSONRPC Error Response"))

  case class Notification(method: String, params: Option[JsonObject]) extends Message
  object Notification:
    given Encoder[Notification] = Message.given_Encoder_Message.contramap(identity)
    given Decoder[Notification] = Message.given_Decoder_Message.emap(_ match
      case n: Notification => Right(n)
      case other           => Left(s"Not a JSONRPC Notification"))

  enum Id:
    case IdString(id: String)
    case IdInt(id: Long)

  enum ErrorCode:
    case ParseError
    case InvalidRequest
    case MethodNotFound
    case InvalidParams
    case InternalError
    case Other(code: Int)
  object ErrorCode:
    def fromInt(code: Int): ErrorCode = code match
      case -32700 => ErrorCode.ParseError
      case -32600 => ErrorCode.InvalidRequest
      case -32601 => ErrorCode.MethodNotFound
      case -32602 => ErrorCode.InvalidParams
      case -32603 => ErrorCode.InternalError
      case code   => ErrorCode.Other(code)
    def toInt(code: ErrorCode): Int = code match
      case ErrorCode.ParseError     => -32700
      case ErrorCode.InvalidRequest => -32600
      case ErrorCode.MethodNotFound => -32601
      case ErrorCode.InvalidParams  => -32602
      case ErrorCode.InternalError  => -32603
      case ErrorCode.Other(code)    => code
    given Encoder[ErrorCode] = Encoder.encodeInt.contramap(ErrorCode.toInt)
    given Decoder[ErrorCode] = Decoder.decodeInt.map(ErrorCode.fromInt)

  given Decoder[Id] = Decoder.instance { c =>
    c.as[Long].map(Id.IdInt.apply)
      .orElse(c.as[String].map(Id.IdString.apply))
  }
  given Encoder[Id] = Encoder.instance {
    case Id.IdInt(i)    => i.asJson
    case Id.IdString(s) => s.asJson
  }

  object Message:
    given Decoder[Message] = Decoder.instance { c =>
      val version = c.downField("jsonrpc").as[String]
      if version != Right("2.0") then
        Left(DecodingFailure(s"Missing or invalid jsonrpc version: $version", c.history))
      else
        val methodOpt = c.downField("method").as[String].toOption
        val idOpt = c.downField("id").as[Id].toOption
        val resultOpt = c.downField("result").as[JsonObject].toOption
        val errorObjOpt = c.downField("error").as[JsonObject].toOption
        val paramsOpt = c.downField("params").as[JsonObject].toOption
        (methodOpt, idOpt, resultOpt, errorObjOpt) match
          // notification: has method, no id
          case (Some(m), None, _, _) =>
            Right(Notification(m, paramsOpt))

          // request: has method and id
          case (Some(m), Some(id), _, _) =>
            Right(Request(id, m, paramsOpt))

          // success response: has id and result
          case (_, Some(id), Some(r), _) =>
            Right(Response.Success(id, r))

          // error response: has id and error object
          case (_, Some(id), _, Some(errObj)) =>
            for
              codeJson <- errObj("code")
                .toRight(DecodingFailure("Missing error.code field", c.history))
              code <- codeJson.as[ErrorCode]
              msgJson <- errObj("message")
                .toRight(DecodingFailure("Missing error.message field", c.history))
              message <- msgJson.asString
                .toRight(DecodingFailure("error.message is not a string", c.history))
            yield Response.Error(id, code, message, errObj("data"))

          case _ =>
            Left(DecodingFailure("unrecognized JSON-RPC message", c.history))
    }

    given Encoder[Message] = Encoder.instance {
      case Request(id, method, params) =>
        Json.obj(
          ("jsonrpc", Json.fromString("2.0")),
          ("id", id.asJson),
          ("method", method.asJson),
        ).deepMerge(
          params.map(p => Json.obj("params" -> p.asJson)).getOrElse(Json.obj())
        )
      case Notification(method, params) =>
        Json.obj(
          ("jsonrpc", Json.fromString("2.0")),
          ("method", method.asJson),
        ).deepMerge(
          params.map(p => Json.obj("params" -> p.asJson)).getOrElse(Json.obj())
        )
      case Response.Success(id, result) =>
        Json.obj(
          ("jsonrpc", Json.fromString("2.0")),
          ("id", id.asJson),
          ("result", result.asJson),
        )
      case Response.Error(id, code, message, data) =>
        val errorFields = List(
          Some("code" -> code.asJson),
          Some("message" -> message.asJson),
          data.map(d => "data" -> d),
        ).flatten
        Json.obj(
          ("jsonrpc", Json.fromString("2.0")),
          ("id", id.asJson),
          ("error", Json.obj(errorFields*)),
        )
    }
