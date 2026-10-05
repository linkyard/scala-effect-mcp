package ch.linkyard.mcp.protocol

import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.protocol.McpCodec.DecodeError
import ch.linkyard.mcp.protocol.McpCodec.fromJsonRpc
import ch.linkyard.mcp.protocol.McpCodec.toJsonRpc
import io.circe.Json
import io.circe.JsonObject
import io.circe.literal.*
import io.circe.syntax.*
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class McpCodecSpec extends AnyFunSpec with Matchers with EitherValues with OptionValues:
  private val meta = json"""{
    "io.modelcontextprotocol/protocolVersion": "2026-07-28",
    "io.modelcontextprotocol/clientCapabilities": {}
  }""".asObject.get

  private def request(method: String, params: JsonObject): JsonRpc.Request =
    JsonRpc.Request(JsonRpc.Id.IdInt(1), method, Some(params.add("_meta", meta.asJson)))

  describe("McpCodec") {
    describe("decodeRequest") {
      it("should decode a tools/list request with a cursor") {
        val decoded = McpCodec.decodeRequest(request("tools/list", JsonObject("cursor" -> "abc".asJson)))
        decoded.value shouldBe Tool.ListTools(Some("abc"), Meta(meta))
      }

      it("should decode a tools/call request without arguments") {
        val decoded = McpCodec.decodeRequest(request("tools/call", JsonObject("name" -> "tool".asJson)))
        decoded.value shouldBe Tool.CallTool("tool", None, None, None, Meta(meta))
      }

      it("should decode the input responses and the request state of a retry") {
        val params = JsonObject(
          "name" -> "tool".asJson,
          "inputResponses" -> json"""{"a": {"action": "accept", "content": {"name": "x"}}}""",
          "requestState" -> "state".asJson,
        )
        val decoded = McpCodec.decodeRequest(request("tools/call", params)).value.asInstanceOf[Tool.CallTool]
        decoded.requestState shouldBe Some("state")
        decoded.inputResponses.value.elicitResult("a").value.value.action shouldBe ElicitAction.Accept
      }

      it("should fail for an unknown method") {
        McpCodec.decodeRequest(request("does/not/exist", JsonObject.empty)).left.value shouldBe
          DecodeError.UnknownMethod("does/not/exist")
      }

      it("should not know the methods of the earlier versions") {
        McpCodec.decodeRequest(request("ping", JsonObject.empty)).left.value shouldBe DecodeError.UnknownMethod("ping")
        McpCodec.decodeRequest(request("initialize", JsonObject.empty)).left.value shouldBe
          DecodeError.UnknownMethod("initialize")
      }

      it("should fail for invalid params") {
        McpCodec.decodeRequest(request("tools/call", JsonObject.empty)).left.value shouldBe a[DecodeError.InvalidParams]
        McpCodec.decodeRequest(request("resources/read", JsonObject("uri" -> 1.asJson))).left.value shouldBe
          a[DecodeError.InvalidParams]
      }
    }

    describe("decodeNotification") {
      it("should decode a cancellation") {
        val n = JsonRpc.Notification("notifications/cancelled", Some(JsonObject("requestId" -> 7.asJson)))
        McpCodec.decodeNotification(n).value shouldBe Cancelled(RequestId.IdNumber(7))
      }

      it("should fail for other notifications") {
        McpCodec.decodeNotification(JsonRpc.Notification("notifications/initialized", None)).left.value shouldBe
          DecodeError.UnknownMethod("notifications/initialized")
      }
    }

    describe("encodeResponse") {
      it("should mark results as complete") {
        val response = McpCodec.encodeResponse(RequestId.IdString("1"), Tool.ListTools.Response(Nil))
        response.result shouldBe
          json"""{"resultType": "complete", "tools": [], "ttlMs": 0, "cacheScope": "private"}""".asObject.get
      }

      it("should mark input required results") {
        val state = InputRequiredResult(requestState = Some("s"))
        val response = McpCodec.encodeResponse(RequestId.IdString("1"), state)
        response.result shouldBe json"""{"resultType": "input_required", "requestState": "s"}""".asObject.get
      }

      it("should write a failed tool call with isError") {
        val response = McpCodec.encodeResponse(
          RequestId.IdString("1"),
          Tool.CallTool.Response.Error(List(Content.Text("failed"))),
        )
        response.result("isError") shouldBe Some(Json.True)
      }

      it("should not write null values") {
        val response = McpCodec.encodeResponse(
          RequestId.IdString("1"),
          Prompts.GetPrompt.Response(List(PromptMessage(Role.User, Content.Text("hi")))),
        )
        response.asJson.noSpaces should not include "null"
      }

      it("should keep nulls in structured content") {
        val response = McpCodec.encodeResponse(
          RequestId.IdString("1"),
          Tool.CallTool.Response.Success(Nil, Some(json"""{"a": null}""")),
        )
        response.result("structuredContent").value shouldBe json"""{"a": null}"""
      }
    }

    describe("encodeNotification") {
      it("should omit empty params") {
        McpCodec.encodeNotification(Tool.ListChanged()) shouldBe
          JsonRpc.Notification("notifications/tools/list_changed", None)
      }

      it("should write the subscription id") {
        val notification = Resources.Updated("file:///a", Meta(Meta.Key.SubscriptionId -> 3.asJson))
        McpCodec.encodeNotification(notification).params.value("_meta").value shouldBe
          json"""{"io.modelcontextprotocol/subscriptionId": 3}"""
      }
    }

    describe("ids") {
      it("should convert between the id types") {
        JsonRpc.Id.IdString("a").fromJsonRpc shouldBe RequestId.IdString("a")
        JsonRpc.Id.IdInt(2).fromJsonRpc shouldBe RequestId.IdNumber(2)
        RequestId.IdString("a").toJsonRpc shouldBe JsonRpc.Id.IdString("a")
        RequestId.IdNumber(2).toJsonRpc shouldBe JsonRpc.Id.IdInt(2)
      }
    }
  }
