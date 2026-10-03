package ch.linkyard.mcp.server

import cats.effect.IO
import cats.effect.Ref
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.McpCodec.fromJsonRpc
import ch.linkyard.mcp.server.McpServer.*
import ch.linkyard.mcp.server.TestSupport.*
import io.circe.Json
import io.circe.JsonObject
import io.circe.literal.*
import io.circe.syntax.*
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

class ModernHandlerSpec extends AnyFunSpec with Matchers with OptionValues with EitherValues:
  private case class Fixture(server: FixtureServer, handler: JsonRpcHandler[IO], errors: Ref[IO, List[Throwable]])

  private def withFixture[A](test: Fixture => IO[A], config: McpServerConfig = McpServerConfig(supportLegacyClients = false))
    : A =
    (for
      server <- FixtureServer.create
      errors <- Ref.of[IO, List[Throwable]](Nil)
      handler = server.handlerFactory(config, e => errors.update(_ :+ e)).stateless
      result <- test(Fixture(server, handler, errors))
    yield result).run

  private def call(f: Fixture, id: Int, method: String, params: (String, Json)*): IO[List[JsonRpc.Message]] =
    messages(f.handler, request(id, method, params*))

  describe("A server (2026-07-28)") {
    describe("server/discover") {
      it("should return the supported versions, the capabilities and the instructions") {
        val result = withFixture(f => call(f, 1, "server/discover")).result
        result("resultType") shouldBe Some("complete".asJson)
        result("supportedVersions") shouldBe Some(json"""["2026-07-28"]""")
        result("instructions") shouldBe Some("use the tools".asJson)
        val capabilities = result("capabilities").value.as[ServerCapabilities].value
        capabilities.tools shouldBe Some(ServerCapabilities.Tools(Some(true)))
        capabilities.prompts shouldBe Some(ServerCapabilities.Prompts(Some(false)))
        capabilities.resources shouldBe Some(ServerCapabilities.Resources(Some(true), Some(true)))
        capabilities.completions shouldBe defined
        capabilities.logging shouldBe None
      }

      it("should identify the server in the meta of the result") {
        val result = withFixture(f => call(f, 1, "server/discover")).result
        result("_meta").value.hcursor.downField("io.modelcontextprotocol/serverInfo").as[Implementation].value shouldBe
          Implementation("fixture", "1.2.3")
      }
    }

    describe("validation of the request") {
      it("should reject requests without the protocol version") {
        val rpc = JsonRpc.Request(
          JsonRpc.Id.IdInt(1),
          "tools/list",
          Some(JsonObject("_meta" -> json"""{"io.modelcontextprotocol/clientCapabilities": {}}""")),
        )
        val error = withFixture(f => messages(f.handler, rpc)).error
        error.code shouldBe ErrorCode.InvalidParams
        error.message should include("protocolVersion")
      }

      it("should reject requests without the client capabilities") {
        val rpc = JsonRpc.Request(
          JsonRpc.Id.IdInt(1),
          "tools/list",
          Some(JsonObject("_meta" -> json"""{"io.modelcontextprotocol/protocolVersion": "2026-07-28"}""")),
        )
        withFixture(f => messages(f.handler, rpc)).error.code shouldBe ErrorCode.InvalidParams
      }

      it("should reject requests without params") {
        val rpc = JsonRpc.Request(JsonRpc.Id.IdInt(1), "tools/list", None)
        withFixture(f => messages(f.handler, rpc)).error.code shouldBe ErrorCode.InvalidParams
      }

      it("should reject an unsupported protocol version and list the supported ones") {
        val rpc = requestWithMeta(1, "tools/list", clientMeta(version = "1900-01-01"))
        val error = withFixture(f => messages(f.handler, rpc)).error
        error.code shouldBe McpErrorCode.UnsupportedProtocolVersion
        error.data.value.as[UnsupportedProtocolVersionData].value shouldBe
          UnsupportedProtocolVersionData(List("2026-07-28"), "1900-01-01")
      }

      it("should reject unknown methods") {
        withFixture(f => call(f, 1, "does/not/exist")).error.code shouldBe ErrorCode.MethodNotFound
      }

      it("should reject the methods of the earlier versions") {
        withFixture(f => call(f, 1, "ping")).error.code shouldBe ErrorCode.MethodNotFound
        withFixture(f => call(f, 1, "initialize")).error.code shouldBe ErrorCode.MethodNotFound
      }

      it("should reject invalid params") {
        withFixture(f => call(f, 1, "tools/call", "name" -> 5.asJson)).error.code shouldBe ErrorCode.InvalidParams
      }

      it("should answer with the id of the request") {
        withFixture(f => call(f, 42, "tools/list")).response.id shouldBe JsonRpc.Id.IdInt(42)
        withFixture(f => call(f, 43, "nope")).response.id shouldBe JsonRpc.Id.IdInt(43)
      }

      it("should reject requests for features the server does not have") {
        val bare = new McpServer[IO]:
          override val serverInfo: Implementation = Implementation("bare", "1")
          override def instructions: IO[Option[String]] = IO.pure(None)
        val handler = bare.handlerFactory(McpServerConfig(), _ => IO.unit).stateless
        messages(handler, request(1, "tools/list")).run.error.code shouldBe ErrorCode.MethodNotFound
        messages(handler, request(2, "prompts/list")).run.error.code shouldBe ErrorCode.MethodNotFound
        messages(handler, request(3, "resources/list")).run.error.code shouldBe ErrorCode.MethodNotFound
        val capabilities = messages(handler, request(4, "server/discover")).run.result("capabilities").value
        capabilities shouldBe json"{}"
      }
    }

    describe("tools/list") {
      it("should list the tools sorted by name with annotations and the cache hints") {
        val result = withFixture(f => call(f, 1, "tools/list")).result
        val response = result.asJson.as[Tool.ListTools.Response].value
        response.tools.map(_.name) shouldBe List("add", "ask", "crashing", "echo", "failing", "progress", "slow", "whoami")
        response.tools.find(_.name == "echo").value.annotations.value.readOnlyHint shouldBe Some(true)
        response.tools.find(_.name == "echo").value.description shouldBe Some("the echo tool")
        response.ttlMs shouldBe 300000
        response.cacheScope shouldBe CacheScope.Public
        result("resultType") shouldBe Some("complete".asJson)
      }

      it("should list tools depending on the authentication") {
        val names = (auth: Authentication) =>
          withFixture(f => messages(f.handler, request(1, "tools/list"), auth)).result.asJson
            .as[Tool.ListTools.Response].value.tools.map(_.name)
        names(Authentication.Anonymous) should not contain "admin"
        names(Authentication.BearerToken("admin")) should contain("admin")
      }

      it("should list the changed tools") {
        val names = withFixture(f =>
          f.server.setTools() >> call(f, 1, "tools/list")
        ).result.asJson.as[Tool.ListTools.Response].value.tools
        names shouldBe empty
      }
    }

    describe("tools/call") {
      it("should call a text tool") {
        val result = withFixture(f => call(f, 1, "tools/call", "name" -> "echo".asJson, "arguments" -> json"""{"text": "hi"}""")).result
        result.asJson.as[Tool.CallTool.Response].value shouldBe a[Tool.CallTool.Response.Success]
        result("content") shouldBe Some(json"""[{"type": "text", "text": "hi"}]""")
        result("resultType") shouldBe Some("complete".asJson)
      }

      it("should call a structured tool") {
        val result = withFixture(f =>
          call(f, 1, "tools/call", "name" -> "add".asJson, "arguments" -> json"""{"a": 1, "b": 2}""")
        ).result
        result("structuredContent") shouldBe Some(json"""{"total": 3}""")
      }

      it("should report invalid arguments as error of the tool call") {
        val result = withFixture(f =>
          call(f, 1, "tools/call", "name" -> "add".asJson, "arguments" -> json"""{"a": "x"}""")
        ).result
        result("isError") shouldBe Some(Json.True)
        result.asJson.noSpaces should include("Invalid arguments")
      }

      it("should report missing arguments as error of the tool call") {
        val result = withFixture(f => call(f, 1, "tools/call", "name" -> "add".asJson)).result
        result("isError") shouldBe Some(Json.True)
      }

      it("should report the failure of a tool as error of the tool call") {
        val result = withFixture(f =>
          call(f, 1, "tools/call", "name" -> "failing".asJson, "arguments" -> json"""{"text": "x"}""")
        ).result
        result("isError") shouldBe Some(Json.True)
        result("content") shouldBe Some(json"""[{"type": "text", "text": "it failed"}]""")
      }

      it("should report unknown tools as invalid params") {
        val error = withFixture(f => call(f, 1, "tools/call", "name" -> "nope".asJson)).error
        error.code shouldBe ErrorCode.InvalidParams
        error.message should include("nope")
      }

      it("should answer with an internal error and log the exception when a tool crashes") {
        val (messages, errors) = withFixture(f =>
          call(f, 1, "tools/call", "name" -> "crashing".asJson, "arguments" -> json"""{"text": "x"}""")
            .product(f.errors.get)
        )
        messages.error.code shouldBe ErrorCode.InternalError
        messages.error.message shouldBe "Internal error"
        errors.map(_.getMessage) shouldBe List("boom")
      }

      it("should send the progress before the result when the client asks for it") {
        val rpc = requestWithMeta(
          1,
          "tools/call",
          clientMeta(extra = "progressToken" -> "p1".asJson),
          "name" -> "progress".asJson,
          "arguments" -> json"""{"text": "x"}""",
        )
        val all = withFixture(f => messages(f.handler, rpc))
        all.notifications.map(_.method) shouldBe List("notifications/progress", "notifications/progress")
        all.notifications.head.params.value("progressToken") shouldBe Some("p1".asJson)
        all.notifications.head.params.value("total") shouldBe Some(2.asJson)
        all.notifications.head.params.value("message") shouldBe Some("half".asJson)
        all.last shouldBe a[JsonRpc.Response.Success]
      }

      it("should not send progress when the client did not ask for it") {
        val all = withFixture(f => call(f, 1, "tools/call", "name" -> "progress".asJson, "arguments" -> json"""{"text": "x"}"""))
        all.notifications shouldBe empty
      }

      it("should tell the tool who the client is") {
        val (text, seen) = withFixture(f =>
          for
            result <- messages(
              f.handler,
              request(1, "tools/call", "name" -> "whoami".asJson, "arguments" -> json"""{"text": "x"}"""),
              Authentication.BearerToken("secret"),
            )
            seen <- f.server.seen.get
          yield result.result("content") -> seen
        )
        text.value.noSpaces should include("BearerToken(secret)")
        seen.head.client.info shouldBe Some(Implementation("test", "1"))
        seen.head.client.capabilities.supportsFormElicitation shouldBe true
        seen.head.input shouldBe InputContext.empty
        seen.head.transport shouldBe a[ch.linkyard.mcp.jsonrpc2.JsonRpcConnection.Info.Other]
      }

      it("should cancel the tool when the stream of the request is cancelled") {
        val cancelled = withFixture(f =>
          for
            fiber <- messages(f.handler, request(1, "tools/call", "name" -> "slow".asJson, "arguments" -> json"""{"text": "x"}""")).start
            _ <- f.server.started.get
            _ <- fiber.cancel
            _ <- f.server.cancelled.get.timeout(5.seconds)
          yield "cancelled"
        )
        cancelled shouldBe "cancelled"
      }
    }

    describe("input required") {
      def ask(f: Fixture, id: Int, extra: (String, Json)*): IO[List[JsonRpc.Message]] =
        call(f, id, "tools/call", ("name" -> "ask".asJson) +: extra*)

      it("should ask for input and protect the state") {
        val result = withFixture(f => ask(f, 1)).result
        result("resultType") shouldBe Some("input_required".asJson)
        result("inputRequests").value.hcursor.downField("name").downField("method").as[String].value shouldBe "elicitation/create"
        val state = result("requestState").value.asString.value
        state should not include "asked" // not readable
        state should include(".")
      }

      it("should complete the request with the answers and the state of the retry") {
        val result = withFixture(f =>
          for
            first <- ask(f, 1)
            state = first.result("requestState").value
            second <- ask(
              f,
              2,
              "inputResponses" -> json"""{"name": {"action": "accept", "content": {"name": "Ada"}}}""",
              "requestState" -> state,
            )
          yield second.result
        )
        result("resultType") shouldBe Some("complete".asJson)
        result("content") shouldBe Some(json"""[{"type": "text", "text": "hello Ada (asked)"}]""")
      }

      it("should ask again when the answers are missing") {
        val result = withFixture(f =>
          for
            first <- ask(f, 1)
            second <- ask(f, 2, "inputResponses" -> json"""{}""", "requestState" -> first.result("requestState").value)
          yield second.result
        )
        result("resultType") shouldBe Some("input_required".asJson)
      }

      it("should reject a modified state") {
        val error = withFixture(f =>
          for
            first <- ask(f, 1)
            state = first.result("requestState").value.asString.value
            second <- ask(f, 2, "requestState" -> (state.take(5) + "x" + state.drop(6)).asJson)
          yield second.error
        )
        error.code shouldBe ErrorCode.InvalidParams
        error.message shouldBe "Invalid request state"
      }

      it("should reject garbage as state") {
        withFixture(f => ask(f, 1, "requestState" -> "garbage".asJson)).error.code shouldBe ErrorCode.InvalidParams
      }

      it("should reject a state that was created for another tool") {
        val error = withFixture(f =>
          for
            first <- ask(f, 1)
            second <- call(
              f,
              2,
              "tools/call",
              "name" -> "echo".asJson,
              "arguments" -> json"""{"text": "x"}""",
              "requestState" -> first.result("requestState").value,
            )
          yield second.error
        )
        error.message shouldBe "Invalid request state"
      }

      it("should reject a state that was created for another user") {
        val error = withFixture(f =>
          for
            first <- messages(f.handler, request(1, "tools/call", "name" -> "ask".asJson), Authentication.BearerToken("a"))
            second <- messages(
              f.handler,
              request(2, "tools/call", "name" -> "ask".asJson, "requestState" -> first.result("requestState").value),
              Authentication.BearerToken("b"),
            )
          yield second.error
        )
        error.message shouldBe "Invalid request state"
      }

      it("should accept the state for the same user") {
        val result = withFixture(f =>
          for
            first <- messages(f.handler, request(1, "tools/call", "name" -> "ask".asJson), Authentication.BearerToken("a"))
            second <- messages(
              f.handler,
              request(2, "tools/call", "name" -> "ask".asJson, "requestState" -> first.result("requestState").value),
              Authentication.BearerToken("a"),
            )
          yield second.result
        )
        result("resultType") shouldBe Some("input_required".asJson)
      }

      it("should reject an expired state") {
        val config = McpServerConfig(
          requestState = RequestStateConfig(List(Array.fill(32)(1.toByte)), timeToLive = 0.seconds),
          supportLegacyClients = false,
        )
        val error = withFixture(
          f =>
            for
              first <- ask(f, 1)
              _ <- IO.sleep(1100.millis)
              second <- ask(f, 2, "requestState" -> first.result("requestState").value)
            yield second.error,
          config,
        )
        error.message shouldBe "Request state expired"
      }

      it("should require the elicitation capability of the client") {
        val rpc = requestWithMeta(1, "tools/call", clientMeta(capabilities = json"{}"), "name" -> "ask".asJson)
        val error = withFixture(f => messages(f.handler, rpc)).error
        error.code shouldBe McpErrorCode.MissingRequiredClientCapability
        error.data.value.as[MissingRequiredClientCapabilityData].value.requiredCapabilities.elicitation shouldBe defined
      }
    }

    describe("prompts") {
      it("should list the prompts with the cache hints") {
        val response = withFixture(f => call(f, 1, "prompts/list")).result.asJson.as[Prompts.ListPrompts.Response].value
        response.prompts.map(_.name) shouldBe List("greet")
        response.ttlMs shouldBe 60000
        response.cacheScope shouldBe CacheScope.Private
      }

      it("should get a prompt") {
        val result = withFixture(f =>
          call(f, 1, "prompts/get", "name" -> "greet".asJson, "arguments" -> json"""{"name": "Ada"}""")
        ).result
        result("description") shouldBe Some("a greeting".asJson)
        result("messages").value.noSpaces should include("hello Ada")
      }

      it("should fail for unknown prompts") {
        withFixture(f => call(f, 1, "prompts/get", "name" -> "nope".asJson)).error.code shouldBe ErrorCode.InvalidParams
      }
    }

    describe("resources") {
      it("should page through the resources") {
        val (first, second, third) = withFixture(f =>
          for
            first <- call(f, 1, "resources/list")
            c1 = first.result("nextCursor").value
            second <- call(f, 2, "resources/list", "cursor" -> c1)
            c2 = second.result("nextCursor").value
            third <- call(f, 3, "resources/list", "cursor" -> c2)
          yield (first.result, second.result, third.result)
        )
        first("resources").value.asArray.value.map(_.hcursor.get[String]("name").value) shouldBe Vector("a", "b")
        second("resources").value.asArray.value.map(_.hcursor.get[String]("name").value) shouldBe Vector("c", "d")
        third("resources").value.asArray.value.map(_.hcursor.get[String]("name").value) shouldBe Vector("e")
        third("nextCursor") shouldBe None
        first("ttlMs") shouldBe Some(10000.asJson)
        first("cacheScope") shouldBe Some("public".asJson)
      }

      it("should list the resource templates") {
        val result = withFixture(f => call(f, 1, "resources/templates/list")).result
        result("resourceTemplates").value.asArray.value.size shouldBe 1
        result("nextCursor") shouldBe None
      }

      it("should read a resource") {
        val result = withFixture(f => call(f, 1, "resources/read", "uri" -> "test://a".asJson)).result
        result("contents").value.noSpaces should include("content of test://a")
        result("ttlMs") shouldBe Some(1000.asJson)
      }

      it("should fail with invalid params for a resource that does not exist") {
        val error = withFixture(f => call(f, 1, "resources/read", "uri" -> "test://z".asJson)).error
        error.code shouldBe ErrorCode.InvalidParams
        error.data.value shouldBe json"""{"uri": "test://z"}"""
      }
    }

    describe("completion") {
      it("should complete the arguments of a prompt") {
        val result = withFixture(f =>
          call(
            f,
            1,
            "completion/complete",
            "ref" -> json"""{"type": "ref/prompt", "name": "greet"}""",
            "argument" -> json"""{"name": "name", "value": "a"}""",
          )
        ).result
        result("completion") shouldBe Some(json"""{"values": ["alice"]}""")
      }

      it("should complete the arguments of a resource template") {
        val result = withFixture(f =>
          call(
            f,
            1,
            "completion/complete",
            "ref" -> json"""{"type": "ref/resource", "uri": "test://{name}"}""",
            "argument" -> json"""{"name": "name", "value": ""}""",
          )
        ).result
        result("completion") shouldBe Some(json"""{"values": ["a", "b"]}""")
      }
    }

    describe("subscriptions/listen") {
      def listen(f: Fixture, filter: Json): fs2.Stream[IO, JsonRpc.Message] =
        f.handler.request(request(7, "subscriptions/listen", "notifications" -> filter), context())

      it("should acknowledge what the server accepts first") {
        val first = withFixture(f =>
          listen(f, json"""{"toolsListChanged": true, "promptsListChanged": true, "resourceSubscriptions": ["test://a"]}""")
            .take(1).compile.toList
        )
        val ack = first.head.asInstanceOf[JsonRpc.Notification]
        ack.method shouldBe "notifications/subscriptions/acknowledged"
        ack.params.value("notifications") shouldBe Some(json"""{"toolsListChanged": true, "resourceSubscriptions": ["test://a"]}""")
        ack.params.value("_meta") shouldBe Some(json"""{"io.modelcontextprotocol/subscriptionId": 7}""")
      }

      it("should send the notifications that were asked for tagged with the subscription id") {
        val received = withFixture(f =>
          for
            fiber <- listen(f, json"""{"toolsListChanged": true, "resourceSubscriptions": ["test://a"]}""")
              .take(3).compile.toList.start
            _ <- IO.sleep(300.millis)
            _ <- f.server.changes.publish1(())
            _ <- f.server.updates.publish1("test://other") // not subscribed
            _ <- f.server.updates.publish1("test://a")
            messages <- fiber.joinWithNever
          yield messages.collect { case n: JsonRpc.Notification => n }
        )
        received.head.method shouldBe "notifications/subscriptions/acknowledged"
        // the order of notifications of different sources is not defined
        received.tail.map(_.method).toSet shouldBe
          Set("notifications/tools/list_changed", "notifications/resources/updated")
        received.tail.foreach(
          _.params.value("_meta") shouldBe Some(json"""{"io.modelcontextprotocol/subscriptionId": 7}""")
        )
        received.find(_.method == "notifications/resources/updated").value.params.value("uri") shouldBe
          Some("test://a".asJson)
      }

      it("should not send what was not asked for") {
        val received = withFixture(f =>
          for
            fiber <- listen(f, json"""{"resourceSubscriptions": ["test://a"]}""").take(2).compile.toList.start
            _ <- IO.sleep(300.millis)
            _ <- f.server.changes.publish1(()) // tools are not subscribed
            _ <- f.server.updates.publish1("test://a")
            messages <- fiber.joinWithNever
          yield messages.collect { case n: JsonRpc.Notification => n.method }
        )
        received shouldBe List("notifications/subscriptions/acknowledged", "notifications/resources/updated")
      }

      it("should end the subscription at once when the server accepts nothing") {
        val received = withFixture(f => listen(f, json"""{"promptsListChanged": true}""").compile.toList)
        received.map {
          case n: JsonRpc.Notification => n.method
          case _: JsonRpc.Response.Success => "result"
          case _ => "other"
        } shouldBe List("notifications/subscriptions/acknowledged", "result")
        received.head.asInstanceOf[JsonRpc.Notification].params.value("notifications") shouldBe Some(json"{}")
        val last = received.last.asInstanceOf[JsonRpc.Response.Success].result
        last("resultType") shouldBe Some("complete".asJson)
        last("_meta").value.hcursor.get[Int]("io.modelcontextprotocol/subscriptionId").value shouldBe 7
      }

      it("should stop when the stream is cancelled") {
        val done = withFixture(f =>
          for
            fiber <- listen(f, json"""{"toolsListChanged": true}""").compile.drain.start
            _ <- IO.sleep(200.millis)
            _ <- fiber.cancel
            outcome <- fiber.join
          yield outcome.isCanceled
        )
        done shouldBe true
      }
    }

    describe("cancellation") {
      it("should know which request a cancellation is for") {
        val handler = withFixture(f => IO.pure(f.handler))
        handler.cancelledRequest(JsonRpc.Notification("notifications/cancelled", Some(JsonObject("requestId" -> 5.asJson)))) shouldBe
          Some(JsonRpc.Id.IdInt(5))
        handler.cancelledRequest(JsonRpc.Notification("notifications/other", None)) shouldBe None
        handler.cancelledRequest(JsonRpc.Notification("notifications/cancelled", None)) shouldBe None
      }
    }
  }
