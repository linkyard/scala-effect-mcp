package ch.linkyard.mcp.protocol

import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Codec
import io.circe.Encoder.encodeJsonObject
import io.circe.literal.*
import io.circe.parser.*
import io.circe.syntax.*
import org.scalatest.EitherValues.*
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec

class ElicitationSpec extends AnyFunSpec with OptionValues {
  describe("Elicitation messages") {
    describe("Create request") {
      it("should serialize create request correctly") {
        val create = Elicitation.Create(
          message = "Please provide your name and age",
          requestedSchema = io.circe.JsonObject(
            "type" -> "object".asJson,
            "properties" -> io.circe.JsonObject(
              "name" -> io.circe.JsonObject("type" -> "string".asJson).asJson,
              "age" -> io.circe.JsonObject("type" -> "integer".asJson).asJson,
            ).asJson,
            "required" -> List("name", "age").asJson,
          ),
        )

        val jsonRpc = Codec.encodeServerRequest(RequestId.IdNumber(1), create)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "elicitation/create",
          "params": {
            "message": "Please provide your name and age",
            "requestedSchema": {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string"
                },
                "age": {
                  "type": "integer"
                }
              },
              "required": ["name", "age"]
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize create request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "elicitation/create",
          "params": {
            "message": "Please provide your name and age",
            "requestedSchema": {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string"
                },
                "age": {
                  "type": "integer"
                }
              },
              "required": ["name", "age"]
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "elicitation/create")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val create = decode[Elicitation.Create](params.asJson.noSpaces)
        assert(create.isRight)
        assert(create.value.message == "Please provide your name and age")
        assert(create.value.requestedSchema.asJson.hcursor.downField("type").as[String].contains("object"))
      }
    }

    describe("Create response") {
      it("should serialize create response correctly") {
        val response = Elicitation.Create.Response(
          action = Elicitation.Action.Accept,
          content = Some(io.circe.JsonObject(
            "name" -> "John Doe".asJson,
            "age" -> 30.asJson,
          )),
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "action": "accept",
            "content": {
              "name": "John Doe",
              "age": 30
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize create response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "action": "accept",
            "content": {
              "name": "John Doe",
              "age": 30
            }
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Elicitation.Create.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.action == Elicitation.Action.Accept)
        assert(response.value.content.isDefined)
        assert(response.value.content.get.asJson.hcursor.downField("name").as[String].contains("John Doe"))
      }
    }

    describe("Action enum") {
      it("should serialize Action values correctly") {
        assert(Elicitation.Action.Accept.asJson == "accept".asJson)
        assert(Elicitation.Action.Decline.asJson == "decline".asJson)
        assert(Elicitation.Action.Cancel.asJson == "cancel".asJson)
      }

      it("should deserialize Action values correctly") {
        assert(decode[Elicitation.Action]("\"accept\"").value == Elicitation.Action.Accept)
        assert(decode[Elicitation.Action]("\"decline\"").value == Elicitation.Action.Decline)
        assert(decode[Elicitation.Action]("\"cancel\"").value == Elicitation.Action.Cancel)
      }

      it("should handle unknown action values") {
        val result = decode[Elicitation.Action]("\"unknown\"")
        assert(result.isLeft)
      }
    }

    describe("Create response with decline action") {
      it("should serialize decline response correctly") {
        val response = Elicitation.Create.Response(
          action = Elicitation.Action.Decline,
          content = None,
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "action": "decline"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize decline response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "action": "decline"
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Elicitation.Create.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.action == Elicitation.Action.Decline)
        assert(response.value.content.isEmpty)
      }
    }

    describe("Create response with cancel action") {
      it("should serialize cancel response correctly") {
        val response = Elicitation.Create.Response(
          action = Elicitation.Action.Cancel,
          content = Some(io.circe.JsonObject("reason" -> "User cancelled".asJson)),
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "action": "cancel",
            "content": {
              "reason": "User cancelled"
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize cancel response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "action": "cancel",
            "content": {
              "reason": "User cancelled"
            }
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Elicitation.Create.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.action == Elicitation.Action.Cancel)
        assert(response.value.content.isDefined)
        assert(response.value.content.get.asJson.hcursor.downField("reason").as[String].contains("User cancelled"))
      }
    }
  }
}
