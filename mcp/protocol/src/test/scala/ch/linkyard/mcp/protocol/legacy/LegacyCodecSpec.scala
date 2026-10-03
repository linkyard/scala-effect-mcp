package ch.linkyard.mcp.protocol.legacy

import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.McpCodec.DecodeError
import io.circe.JsonObject
import io.circe.literal.*
import io.circe.syntax.*
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class LegacyCodecSpec extends AnyFunSpec with Matchers with EitherValues with OptionValues:
  private def request(method: String, params: io.circe.Json = json"{}"): JsonRpc.Request =
    JsonRpc.Request(JsonRpc.Id.IdInt(1), method, params.asObject)

  describe("LegacyVersion") {
    it("should negotiate the version") {
      LegacyVersion.negotiate("2025-06-18") shouldBe "2025-06-18"
      LegacyVersion.negotiate("2025-11-25") shouldBe "2025-11-25"
      LegacyVersion.negotiate("2024-11-05") shouldBe "2025-11-25"
      LegacyVersion.negotiate("2026-07-28") shouldBe "2025-11-25"
      LegacyVersion.negotiate("nonsense") shouldBe "2025-11-25"
    }
  }

  describe("LegacyCodec.decodeRequest") {
    it("should decode the initialize request of the specification") {
      val decoded = LegacyCodec.decodeRequest(request(
        "initialize",
        json"""{
          "protocolVersion": "2025-06-18",
          "capabilities": {"roots": {"listChanged": true}, "sampling": {}, "elicitation": {}},
          "clientInfo": {"name": "ExampleClient", "title": "Example Client Display Name", "version": "1.0.0"}
        }""",
      )).value
      val init = decoded.asInstanceOf[Initialize]
      init.protocolVersion shouldBe "2025-06-18"
      init.clientInfo shouldBe Implementation("ExampleClient", "1.0.0", title = Some("Example Client Display Name"))
      init.capabilities.supportsFormElicitation shouldBe true
      init.capabilities.roots shouldBe defined
    }

    it("should decode the capabilities of 2025-11-25") {
      val decoded = LegacyCodec.decodeRequest(request(
        "initialize",
        json"""{
          "protocolVersion": "2025-11-25",
          "capabilities": {"elicitation": {"form": {}, "url": {}}, "tasks": {"requests": {}}},
          "clientInfo": {"name": "c", "version": "1", "description": "d", "websiteUrl": "https://example.com"}
        }""",
      )).value.asInstanceOf[Initialize]
      decoded.capabilities.supportsUrlElicitation shouldBe true
      decoded.clientInfo.websiteUrl shouldBe Some("https://example.com")
    }

    it("should decode the requests that only exist in the earlier versions") {
      LegacyCodec.decodeRequest(request("ping")).value shouldBe Ping()
      LegacyCodec.decodeRequest(request("logging/setLevel", json"""{"level": "debug"}""")).value shouldBe SetLevel("debug")
      LegacyCodec.decodeRequest(request("resources/subscribe", json"""{"uri": "a://b"}""")).value shouldBe Subscribe("a://b")
      LegacyCodec.decodeRequest(request("resources/unsubscribe", json"""{"uri": "a://b"}""")).value shouldBe
        Unsubscribe("a://b")
    }

    it("should decode the requests without the per-request meta") {
      LegacyCodec.decodeRequest(request("tools/list")).value shouldBe Tool.ListTools()
      LegacyCodec.decodeRequest(request("tools/call", json"""{"name": "t", "arguments": {"a": 1}}""")).value shouldBe
        Tool.CallTool("t", Some(json"""{"a": 1}""".asObject.get))
      LegacyCodec.decodeRequest(request("resources/read", json"""{"uri": "a://b"}""")).value shouldBe
        Resources.ReadResource("a://b")
      LegacyCodec.decodeRequest(request("prompts/get", json"""{"name": "p"}""")).value shouldBe Prompts.GetPrompt("p")
    }

    it("should not know the requests of the new version") {
      LegacyCodec.decodeRequest(request("server/discover")).left.value shouldBe DecodeError.UnknownMethod("server/discover")
      LegacyCodec.decodeRequest(request("subscriptions/listen", json"""{"notifications": {}}""")).left.value shouldBe
        DecodeError.UnknownMethod("subscriptions/listen")
    }

    it("should report invalid params") {
      LegacyCodec.decodeRequest(request("initialize")).left.value shouldBe a[DecodeError.InvalidParams]
    }
  }

  describe("LegacyCodec.decodeNotification") {
    it("should decode the notifications that matter") {
      LegacyCodec.decodeNotification(JsonRpc.Notification("notifications/initialized", None)).value shouldBe
        LegacyNotification.Initialized
      LegacyCodec.decodeNotification(
        JsonRpc.Notification("notifications/cancelled", json"""{"requestId": 3, "reason": "x"}""".asObject)
      ).value shouldBe LegacyNotification.Cancelled(Cancelled(RequestId.IdNumber(3), Some("x")))
    }

    it("should ignore the others") {
      LegacyCodec.decodeNotification(JsonRpc.Notification("notifications/roots/list_changed", None)).value shouldBe
        LegacyNotification.Ignored("notifications/roots/list_changed")
    }
  }

  describe("LegacyCodec.encodeResponse") {
    it("should write results as before") {
      val response = LegacyCodec.encodeResponse(
        LegacyVersion.V2025_11_25,
        RequestId.IdNumber(1),
        Tool.ListTools.Response(
          List(Tool("t", inputSchema = JsonObject("type" -> "object".asJson), icons = Some(List(Icon("https://i/x.png"))))),
          ttlMs = 100,
          cacheScope = CacheScope.Public,
          _meta = Meta(Meta.Key.ServerInfo -> Implementation("s", "1").asJson),
        ),
      )
      response.result.keys.toSet shouldBe Set("tools")
      response.result("tools").value.noSpaces should include("icons")
    }

    it("should keep the meta that is not about the server") {
      val response = LegacyCodec.encodeResponse(
        LegacyVersion.V2025_11_25,
        RequestId.IdNumber(1),
        Tool.ListTools.Response(Nil, _meta = Meta("a" -> 1.asJson, Meta.Key.ServerInfo -> Implementation("s", "1").asJson)),
      )
      response.result("_meta") shouldBe Some(json"""{"a": 1}""")
    }

    it("should leave out the icons for 2025-06-18") {
      val tool = Tool("t", inputSchema = JsonObject("type" -> "object".asJson), icons = Some(List(Icon("https://i/x.png"))))
      val schemaWithIconsProperty = JsonObject("type" -> "object".asJson, "properties" -> json"""{"icons": {"type": "string"}}""")
      val response = LegacyCodec.encodeResponse(
        LegacyVersion.V2025_06_18,
        RequestId.IdNumber(1),
        Tool.ListTools.Response(List(tool, tool.copy(name = "u", inputSchema = schemaWithIconsProperty))),
      )
      val tools = response.result("tools").value.asArray.value
      tools.head.asObject.value.contains("icons") shouldBe false
      // properties of the schemas are not touched
      tools(1).noSpaces should include(""""properties":{"icons":{"type":"string"}}""")
    }

    it("should leave out the icons of the resource links for 2025-06-18") {
      val link = Content.ResourceLink("a://b", "b", icons = Some(List(Icon("https://i/x.png"))))
      val response = LegacyCodec.encodeResponse(
        LegacyVersion.V2025_06_18,
        RequestId.IdNumber(1),
        Tool.CallTool.Response.Success(List(link)),
      )
      response.result("content").value.noSpaces should not include "icons"
    }

    it("should leave out the icons of the prompt messages for 2025-06-18") {
      val link = Content.ResourceLink("a://b", "b", icons = Some(List(Icon("https://i/x.png"))))
      val response = LegacyCodec.encodeResponse(
        LegacyVersion.V2025_06_18,
        RequestId.IdNumber(1),
        Prompts.GetPrompt.Response(List(PromptMessage(Role.User, link))),
      )
      response.result("messages").value.noSpaces should not include "icons"
    }

    it("should write the initialize result for 2025-06-18 without the new fields of the server info") {
      val result = InitializeResult(
        "2025-06-18",
        ServerCapabilities(tools = Some(ServerCapabilities.Tools(Some(true)))),
        Implementation("s", "1", title = Some("S"), description = Some("d"), websiteUrl = Some("https://s"), icons = Some(List(Icon("x")))),
        Some("hi"),
      )
      LegacyCodec.encodeInitializeResult(RequestId.IdNumber(1), result).result shouldBe json"""{
        "protocolVersion": "2025-06-18",
        "capabilities": {"tools": {"listChanged": true}},
        "serverInfo": {"name": "s", "version": "1", "title": "S"},
        "instructions": "hi"
      }""".asObject.get
    }

    it("should write the initialize result for 2025-11-25 with all fields of the server info") {
      val result = InitializeResult(
        "2025-11-25",
        ServerCapabilities(),
        Implementation("s", "1", description = Some("d")),
      )
      LegacyCodec.encodeInitializeResult(RequestId.IdNumber(1), result).result("serverInfo") shouldBe
        Some(json"""{"name": "s", "version": "1", "description": "d"}""")
    }

    it("should write empty results") {
      LegacyCodec.encodeEmptyResult(RequestId.IdString("x")).result shouldBe JsonObject.empty
    }
  }

  describe("LegacyCodec elicitation") {
    it("should write the form request without the mode") {
      val schema = json"""{"type": "object", "properties": {}}""".asObject.get
      val request = LegacyCodec.encodeElicitation(RequestId.IdString("s1"), ElicitParams.Form("who?", schema), "e1")
      request.method shouldBe "elicitation/create"
      request.params.value shouldBe json"""{"message": "who?", "requestedSchema": {"type": "object", "properties": {}}}""".asObject.get
    }

    it("should write the url request with the elicitation id") {
      val request = LegacyCodec.encodeElicitation(RequestId.IdString("s1"), ElicitParams.Url("go", "https://x"), "e1")
      request.params.value shouldBe json"""{"mode": "url", "message": "go", "url": "https://x", "elicitationId": "e1"}""".asObject.get
    }

    it("should read the answer") {
      LegacyCodec.decodeElicitResult(
        JsonRpc.Response.Success(JsonRpc.Id.IdInt(1), json"""{"action": "accept", "content": {"a": "b"}}""".asObject.get)
      ) shouldBe ElicitResult(ElicitAction.Accept, json"""{"a": "b"}""".asObject)
    }

    it("should read an error or garbage as cancelled") {
      LegacyCodec.decodeElicitResult(
        JsonRpc.Response.Error(JsonRpc.Id.IdInt(1), JsonRpc.ErrorCode.InternalError, "x", None)
      ) shouldBe ElicitResult(ElicitAction.Cancel)
      LegacyCodec.decodeElicitResult(JsonRpc.Response.Success(JsonRpc.Id.IdInt(1), JsonObject("action" -> 1.asJson))) shouldBe
        ElicitResult(ElicitAction.Cancel)
    }
  }
