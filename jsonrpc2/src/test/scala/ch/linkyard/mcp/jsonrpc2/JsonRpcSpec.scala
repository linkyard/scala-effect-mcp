package ch.linkyard.mcp.jsonrpc2

import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpc.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc.given
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.EitherValues.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

object JsonRpcArbitraries {
  import JsonRpc.*

  val genId: Gen[Id] = Gen.oneOf(
    Gen.alphaNumStr.suchThat(_.nonEmpty).map(Id.IdString.apply),
    Gen.chooseNum(Long.MinValue, Long.MaxValue).map(Id.IdInt.apply),
  )

  implicit val arbId: Arbitrary[Id] = Arbitrary(genId)

  val genJsonObject: Gen[JsonObject] =
    Gen.mapOf(
      Gen.identifier.flatMap(key =>
        Gen.oneOf(
          Gen.const(io.circe.Json.Null),
          Gen.alphaStr.map(io.circe.Json.fromString),
          Gen.chooseNum(Long.MinValue, Long.MaxValue).map(io.circe.Json.fromLong),
          Gen.oneOf(true, false).map(io.circe.Json.fromBoolean),
        ).map(v => key -> v)
      )
    ).map(fields => JsonObject.fromMap(fields))

  val genRequest: Gen[Message] = for {
    id <- genId
    method <- Gen.identifier.suchThat(_.nonEmpty)
    params <- Gen.option(genJsonObject)
  } yield Request(id, method, params)

  val genNotification: Gen[Message] = for {
    method <- Gen.identifier.suchThat(_.nonEmpty)
    params <- Gen.option(genJsonObject)
  } yield Notification(method, params)

  val genSuccess: Gen[Message] = for {
    id <- genId
    result <- genJsonObject
  } yield Response.Success(id, result)

  val genError: Gen[Message] = for {
    id <- genId
    code <- Gen.chooseNum(32600, 32800).map(ErrorCode.fromInt)
    message <- Gen.identifier.suchThat(_.nonEmpty)
    data <- Gen.option(Gen.oneOf(
      Gen.const(io.circe.Json.Null),
      Gen.alphaStr.map(io.circe.Json.fromString),
      Gen.chooseNum(Long.MinValue, Long.MaxValue).map(io.circe.Json.fromLong),
      genJsonObject.map(_.asJson),
    ))
  } yield Response.Error(id, code, message, data)

  val genMessage: Gen[Message] = Gen.oneOf(genRequest, genNotification, genSuccess, genError)
  implicit val arbMessage: Arbitrary[Message] = Arbitrary(genMessage)
}

class JsonRpcSpec extends AnyFunSpec with ScalaCheckPropertyChecks {
  import JsonRpcArbitraries.*
  describe("JsonRpc.Id") {
    describe("encoder") {
      it("should encode a string id as JSON string") {
        val id = Id.IdString("abc123")
        val json = id.asJson
        assert(json.isString)
        assert(json.noSpaces == "\"abc123\"")
      }
      it("should encode an int id as JSON number") {
        val id = Id.IdInt(123L)
        val json = id.asJson
        assert(json.isNumber)
        assert(json.noSpaces == "123")
      }
    }
    describe("decoder") {
      it("should decode a string id") {
        val json = "\"abc123\""
        val result = decode[Id](json)
        assert(result.value == Id.IdString("abc123"))
      }
      it("should decode an int id") {
        val json = "123"
        val result = decode[Id](json)
        assert(result.value == Id.IdInt(123L))
      }
    }
    describe("roundtrip") {
      it("should serialize and deserialize to the same value") {
        forAll { (id: Id) =>
          val json = id.asJson.noSpaces
          val decoded = decode[Id](json)
          assert(decoded == Right(id))
        }
      }
    }
  }

  describe("JsonRpc.Message") {
    describe("decoder") {
      describe("Request") {
        it("should decode a request message with id and method") {
          val json = """{"jsonrpc": "2.0", "id": 1, "method": "foo", "params": {"x": 42}}"""
          val result = decode[Message](json)
          assert(result.value == Request(Id.IdInt(1), "foo", Some(JsonObject("x" -> io.circe.Json.fromInt(42)))))
        }
      }
      describe("Notification") {
        it("should decode a notification message with method and params") {
          val json = """{"jsonrpc": "2.0", "method": "notify", "params": {"y": "bar"}}"""
          val result = decode[Message](json)
          assert(result.value == Notification("notify", Some(JsonObject("y" -> io.circe.Json.fromString("bar")))))
        }
      }
      describe("Response.Success") {
        it("should decode a success response message with id and result") {
          val json = """{"jsonrpc": "2.0", "id": "abc", "result": {"z": true}}"""
          val result = decode[Message](json)
          assert(result.value == Response.Success(Id.IdString("abc"), JsonObject("z" -> io.circe.Json.fromBoolean(true))))
        }
      }
      describe("Response.Error") {
        it("should decode an error response message with id and error object") {
          val json =
            """{"jsonrpc": "2.0", "id": 2, "error": {"code": -32600, "message": "Invalid request", "data": {"foo": "bar"}}}"""
          val result = decode[Message](json)
          result match {
            case Right(Response.Error(Id.IdInt(2), ErrorCode.InvalidRequest, "Invalid request", Some(data))) =>
              assert(data.hcursor.downField("foo").as[String].contains("bar"))
            case _ => fail("Did not decode error response correctly")
          }
        }
      }
      describe("Unrecognized message") {
        it("should fail to decode an unrecognized message") {
          val json = """{"foo": "bar"}"""
          val result = decode[Message](json)
          assert(result.isLeft)
        }
      }
    }

    describe("encoder") {
      describe("Request") {
        it("should encode a request message with id and method") {
          val msg: Message = Request(Id.IdInt(1), "foo", Some(JsonObject("x" -> io.circe.Json.fromInt(42))))
          val json = msg.asJson
          assert(json.hcursor.downField("jsonrpc").as[String].contains("2.0"))
          assert(json.hcursor.downField("id").as[Long].contains(1L))
          assert(json.hcursor.downField("method").as[String].contains("foo"))
          assert(json.hcursor.downField("params").downField("x").as[Int].contains(42))
        }
      }
      describe("Notification") {
        it("should encode a notification message with method and params") {
          val msg: Message = Notification("notify", Some(JsonObject("y" -> io.circe.Json.fromString("bar"))))
          val json = msg.asJson
          assert(json.hcursor.downField("jsonrpc").as[String].contains("2.0"))
          assert(json.hcursor.downField("method").as[String].contains("notify"))
          assert(json.hcursor.downField("params").downField("y").as[String].contains("bar"))
          assert(json.hcursor.downField("id").succeeded == false)
        }
      }
      describe("Response.Success") {
        it("should encode a success response message with id and result") {
          val msg: Message = Response.Success(Id.IdString("abc"), JsonObject("z" -> io.circe.Json.fromBoolean(true)))
          val json = msg.asJson
          assert(json.hcursor.downField("jsonrpc").as[String].contains("2.0"))
          assert(json.hcursor.downField("id").as[String].contains("abc"))
          assert(json.hcursor.downField("result").downField("z").as[Boolean].contains(true))
        }
      }
      describe("Response.Error") {
        it("should encode an error response message with id and error object") {
          val msg: Message = Response.Error(
            Id.IdInt(2),
            ErrorCode.InvalidRequest,
            "Invalid request",
            Some(io.circe.Json.obj("foo" -> io.circe.Json.fromString("bar"))),
          )
          val json = msg.asJson
          assert(json.hcursor.downField("jsonrpc").as[String].contains("2.0"))
          assert(json.hcursor.downField("id").as[Long].contains(2L))
          val error = json.hcursor.downField("error")
          assert(error.downField("code").as[Int].contains(-32600))
          assert(error.downField("message").as[String].contains("Invalid request"))
          assert(error.downField("data").downField("foo").as[String].contains("bar"))
        }
      }
    }
    describe("roundtrip") {
      it("should serialize and deserialize to the same value") {
        forAll { (msg: Message) =>
          val json = msg.asJson.noSpaces
          val decoded = decode[Message](json)
          assert(decoded == Right(msg))
        }
      }
    }
  }
}
