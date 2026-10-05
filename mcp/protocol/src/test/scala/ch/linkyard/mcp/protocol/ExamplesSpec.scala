package ch.linkyard.mcp.protocol

import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.protocol.McpCodec.fromJsonRpc
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

/** Round trips the examples of the specification (schema/2026-07-28/examples). */
class ExamplesSpec extends AnyFunSpec with Matchers:
  private val root: Path = classpathDirectory("/examples/2026-07-28")

  /** sbt 2 serves test resources from a jar; that filesystem has to be opened first. */
  private def classpathDirectory(resource: String): Path =
    val uri = getClass.getResource(resource).toURI
    if uri.getScheme == "jar" then
      val fs =
        try FileSystems.getFileSystem(uri)
        catch
          case _: FileSystemNotFoundException =>
            FileSystems.newFileSystem(uri, Map.empty[String, AnyRef].asJava)
      fs.getPath(resource)
    else Paths.get(uri)

  private def examples(dir: String): List[(String, Json)] =
    val path = root.resolve(dir)
    Files.list(path).iterator().asScala.toList.sortBy(_.getFileName.toString).map { file =>
      file.getFileName.toString -> parse(Files.readString(file)).fold(throw _, identity)
    }

  /** decode -> encode must give the same json again */
  private def roundTrip[A: Decoder: Encoder](dir: String, normalize: Json => Json = identity): Unit =
    describe(dir) {
      examples(dir).foreach { (name, json) =>
        it(s"should round trip $name") {
          val decoded = json.as[A].fold(e => fail(e.getMessage), identity)
          normalize(decoded.asJson) shouldBe normalize(json)
        }
      }
    }

  /** The response to the client is the same, but the optional `isError: false` is not written. */
  private def withoutIsErrorFalse(json: Json): Json =
    json.mapObject(o => if o("isError").contains(Json.False) then o.remove("isError") else o)

  private def request(dir: String): Unit =
    describe(dir) {
      examples(dir).foreach { (name, json) =>
        it(s"should decode and encode $name") {
          val rpc = json.as[JsonRpc.Request].fold(e => fail(e.getMessage), identity)
          val decoded = McpCodec.decodeRequest(rpc).fold(e => fail(e.toString), identity)
          McpCodec.encodeRequest(rpc.id.fromJsonRpc, decoded).asJson shouldBe json
          RequestInfo.fromMeta(decoded.meta).isRight shouldBe true
          decoded.method shouldBe rpc.method
        }
      }
    }

  private def notification[A: Decoder](dir: String)(encode: A => ServerNotification): Unit =
    describe(dir) {
      examples(dir).foreach { (name, json) =>
        it(s"should decode and encode $name") {
          val rpc = json.as[JsonRpc.Notification].fold(e => fail(e.getMessage), identity)
          val params =
            rpc.params.getOrElse(io.circe.JsonObject.empty).asJson.as[A].fold(e => fail(e.getMessage), identity)
          McpCodec.encodeNotification(encode(params)).asJson shouldBe json
        }
      }
    }

  describe("Examples of the specification") {
    describe("data types") {
      roundTrip[Tool]("Tool")
      roundTrip[Resource]("Resource")
      roundTrip[Content]("TextContent")
      roundTrip[Content]("ImageContent")
      roundTrip[Content]("AudioContent")
      roundTrip[Content]("ResourceLink")
      roundTrip[Content]("EmbeddedResource")
      roundTrip[Resource.Contents]("TextResourceContents")
      roundTrip[Resource.Contents]("BlobResourceContents")
      roundTrip[ClientCapabilities]("ClientCapabilities")
      roundTrip[ServerCapabilities]("ServerCapabilities")
      roundTrip[ElicitParams]("ElicitRequestFormParams")
      roundTrip[ElicitParams]("ElicitRequestURLParams")
      roundTrip[ElicitResult]("ElicitResult")
      roundTrip[InputRequest]("ElicitRequest")
      roundTrip[InputResponses]("InputResponses")
      roundTrip[InputRequiredResult]("InputRequiredResult")
    }

    describe("request params") {
      roundTrip[Tool.CallTool]("CallToolRequestParams")
      roundTrip[Prompts.GetPrompt]("GetPromptRequestParams")
      roundTrip[Completion.Complete]("CompleteRequestParams")
      roundTrip[Tool.ListTools]("PaginatedRequestParams")
      roundTrip[Cancelled]("CancelledNotificationParams")
      roundTrip[ProgressNotification]("ProgressNotificationParams")
    }

    describe("results") {
      roundTrip[Tool.CallTool.Response]("CallToolResult", withoutIsErrorFalse)
      roundTrip[Completion.Complete.Response]("CompleteResult")
      roundTrip[Discover.Response]("DiscoverResult")
      roundTrip[Prompts.GetPrompt.Response]("GetPromptResult")
      roundTrip[Prompts.ListPrompts.Response]("ListPromptsResult")
      roundTrip[Resources.ListResources.Response]("ListResourcesResult")
      roundTrip[Resources.ListResourceTemplates.Response]("ListResourceTemplatesResult")
      roundTrip[Tool.ListTools.Response]("ListToolsResult")
      roundTrip[Resources.ReadResource.Response]("ReadResourceResult")
      roundTrip[Subscriptions.Listen.Response]("SubscriptionsListenResult")
    }

    describe("requests") {
      request("CallToolRequest")
      request("CompleteRequest")
      request("DiscoverRequest")
      request("GetPromptRequest")
      request("ListPromptsRequest")
      request("ListResourceTemplatesRequest")
      request("ListResourcesRequest")
      request("ListToolsRequest")
      request("ReadResourceRequest")
      request("SubscriptionsListenRequest")
    }

    describe("notifications") {
      notification[Cancelled]("CancelledNotification")(identity)
      notification[ProgressNotification]("ProgressNotification")(identity)
      notification[Prompts.ListChanged]("PromptListChangedNotification")(identity)
      notification[Resources.ListChanged]("ResourceListChangedNotification")(identity)
      notification[Resources.Updated]("ResourceUpdatedNotification")(identity)
      notification[Subscriptions.Acknowledged]("SubscriptionsAcknowledgedNotification")(identity)
      notification[Tool.ListChanged]("ToolListChangedNotification")(identity)
    }

    describe("errors") {
      def error(dir: String): List[JsonRpc.Response.Error] = examples(dir).map((_, json) =>
        json.as[JsonRpc.Response.Error].fold(e => fail(e.getMessage), identity)
      )
      it("should decode the unsupported protocol version error") {
        error("UnsupportedProtocolVersionError").foreach { e =>
          e.code shouldBe McpErrorCode.UnsupportedProtocolVersion
          e.data.get.as[UnsupportedProtocolVersionData].isRight shouldBe true
        }
      }
      it("should decode the missing required client capability error") {
        error("MissingRequiredClientCapabilityError").foreach { e =>
          e.code shouldBe McpErrorCode.MissingRequiredClientCapability
          e.data.get.as[MissingRequiredClientCapabilityData].isRight shouldBe true
        }
      }
      it("should decode the header mismatch error") {
        error("HeaderMismatchError").foreach(_.code shouldBe McpErrorCode.HeaderMismatch)
      }
    }
  }
