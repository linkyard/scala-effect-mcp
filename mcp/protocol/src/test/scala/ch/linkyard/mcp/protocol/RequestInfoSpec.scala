package ch.linkyard.mcp.protocol

import io.circe.literal.*
import org.scalatest.EitherValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class RequestInfoSpec extends AnyFunSpec with Matchers with EitherValues:
  private def meta(json: io.circe.Json): Meta = Meta(json.asObject.get)

  describe("RequestInfo.fromMeta") {
    it("should read the version, the client info and the capabilities") {
      val info = RequestInfo.fromMeta(meta(json"""{
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientInfo": {"name": "client", "version": "1.2"},
        "io.modelcontextprotocol/clientCapabilities": {"elicitation": {}}
      }"""))
      info.value.protocolVersion shouldBe "2026-07-28"
      info.value.clientInfo shouldBe Some(Implementation("client", "1.2"))
      info.value.clientCapabilities.supportsFormElicitation shouldBe true
      info.value.clientCapabilities.supportsUrlElicitation shouldBe false
    }

    it("should not need the client info") {
      val info = RequestInfo.fromMeta(meta(json"""{
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {}
      }"""))
      info.value.clientInfo shouldBe None
      info.value.clientCapabilities shouldBe ClientCapabilities.empty
    }

    it("should require the protocol version") {
      val info = RequestInfo.fromMeta(meta(json"""{"io.modelcontextprotocol/clientCapabilities": {}}"""))
      info.left.value should include("io.modelcontextprotocol/protocolVersion")
    }

    it("should require the client capabilities") {
      val info = RequestInfo.fromMeta(meta(json"""{"io.modelcontextprotocol/protocolVersion": "2026-07-28"}"""))
      info.left.value should include("io.modelcontextprotocol/clientCapabilities")
    }

    it("should reject invalid values") {
      val info = RequestInfo.fromMeta(meta(json"""{
        "io.modelcontextprotocol/protocolVersion": 5,
        "io.modelcontextprotocol/clientCapabilities": {}
      }"""))
      info.left.value should include("Invalid _meta field io.modelcontextprotocol/protocolVersion")
    }
  }

  describe("ClientCapabilities") {
    it("should know the elicitation modes") {
      ClientCapabilities().supportsFormElicitation shouldBe false
      ClientCapabilities(elicitation = Some(ClientCapabilities.Elicitation())).supportsFormElicitation shouldBe true
      val urlOnly = ClientCapabilities(elicitation = Some(ClientCapabilities.Elicitation(url = Some(io.circe.JsonObject.empty))))
      urlOnly.supportsFormElicitation shouldBe false
      urlOnly.supportsUrlElicitation shouldBe true
    }
  }
