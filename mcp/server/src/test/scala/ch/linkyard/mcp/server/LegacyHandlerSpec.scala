package ch.linkyard.mcp.server

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandlerFactory
import ch.linkyard.mcp.protocol.*
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

/** Clients of the earlier protocol versions (2025-06-18, 2025-11-25) that start with the initialize handshake. */
class LegacyHandlerSpec extends AnyFunSpec with Matchers with OptionValues with EitherValues:
  private val httpInfo = JsonRpcConnection.Info.Http(None, None, Map.empty)

  private def initializeRequest(
    id: Int = 1,
    version: String = "2025-06-18",
    capabilities: Json = json"""{"elicitation": {}}""",
  ): JsonRpc.Request = JsonRpc.Request(
    JsonRpc.Id.IdInt(id),
    "initialize",
    Some(JsonObject(
      "protocolVersion" -> version.asJson,
      "capabilities" -> capabilities,
      "clientInfo" -> json"""{"name": "old client", "version": "0.1"}""",
    )),
  )

  private def plain(id: Int, method: String, params: (String, Json)*): JsonRpc.Request =
    JsonRpc.Request(JsonRpc.Id.IdInt(id), method, Some(JsonObject(params*)))

  private def withSession[A](
    test: (FixtureServer, JsonRpcHandler[IO]) => IO[A],
    config: McpServerConfig = McpServerConfig(),
    info: JsonRpcConnection.Info = httpInfo,
  ): A =
    (for
      server <- FixtureServer.create
      factory = server.handlerFactory(config, _ => IO.unit)
      result <- factory.connection(info).use(handler => test(server, handler))
    yield result).run

  /** Initializes the session (what the client does first) and runs the test */
  private def initialized[A](
    version: String = "2025-06-18",
    capabilities: Json = json"""{"elicitation": {}}""",
    config: McpServerConfig = McpServerConfig(),
    info: JsonRpcConnection.Info = httpInfo,
  )(test: (FixtureServer, JsonRpcHandler[IO]) => IO[A]): A =
    withSession(
      (server, handler) =>
        messages(handler, initializeRequest(version = version, capabilities = capabilities)) >> test(server, handler),
      config,
      info,
    )

  /** Runs the request and answers the requests of the server with `answer`. */
  private def drive(
    handler: JsonRpcHandler[IO],
    request: JsonRpc.Request,
    answer: JsonRpc.Request => JsonRpc.Response,
  ): IO[List[JsonRpc.Message]] =
    handler.request(request, context()).evalTap {
      case r: JsonRpc.Request => handler.response(answer(r), context())
      case _                  => IO.unit
    }.compile.toList.timeout(10.seconds)

  private def accept(name: String)(request: JsonRpc.Request): JsonRpc.Response =
    JsonRpc.Response.Success(request.id, json"""{"action": "accept", "content": {"name": $name}}""".asObject.get)

  describe("A server for legacy clients") {
    describe("initialize") {
      it("should answer with the version of the client when it is supported") {
        val result = withSession((_, h) => messages(h, initializeRequest(version = "2025-06-18"))).result
        result("protocolVersion") shouldBe Some("2025-06-18".asJson)
        withSession((_, h) => messages(h, initializeRequest(version = "2025-11-25"))).result("protocolVersion") shouldBe
          Some("2025-11-25".asJson)
      }

      it("should answer with the newest legacy version for other versions") {
        withSession((_, h) => messages(h, initializeRequest(version = "2024-11-05"))).result("protocolVersion") shouldBe
          Some("2025-11-25".asJson)
        withSession((_, h) => messages(h, initializeRequest(version = "2026-07-28"))).result("protocolVersion") shouldBe
          Some("2025-11-25".asJson)
      }

      it("should return the server info, the capabilities and the instructions") {
        val result = withSession((_, h) => messages(h, initializeRequest())).result
        result("serverInfo") shouldBe Some(json"""{"name": "fixture", "version": "1.2.3"}""")
        result("instructions") shouldBe Some("use the tools".asJson)
        result("capabilities").value.hcursor.downField("tools").downField("listChanged").as[Boolean].value shouldBe true
        result("capabilities").value.hcursor.downField("logging").succeeded shouldBe false
      }

      it("should not contain the fields of the newer version") {
        val result = withSession((_, h) => messages(h, initializeRequest())).result
        result.keys.toSet shouldBe Set("protocolVersion", "capabilities", "serverInfo", "instructions")
      }

      it("should reject invalid params") {
        withSession((_, h) => messages(h, plain(1, "initialize"))).error.code shouldBe ErrorCode.InvalidParams
      }
    }

    describe("before the handshake") {
      it("should reject requests") {
        val error = withSession((_, h) => messages(h, plain(1, "tools/list"))).error
        error.code shouldBe ErrorCode.InvalidRequest
      }

      it("should answer pings") {
        withSession((_, h) => messages(h, plain(1, "ping"))).result shouldBe JsonObject.empty
      }
    }

    describe("requests") {
      it("should answer pings") {
        initialized()((_, h) => messages(h, plain(2, "ping"))).result shouldBe JsonObject.empty
      }

      it("should accept the log level but not use it") {
        initialized()((_, h) => messages(h, plain(2, "logging/setLevel", "level" -> "debug".asJson))).result shouldBe
          JsonObject.empty
      }

      it("should list the tools without the fields of the newer versions") {
        val result = initialized()((_, h) => messages(h, plain(2, "tools/list"))).result
        result.keys.toSet shouldBe Set("tools")
        result("tools").value.asArray.value.size shouldBe 11
      }

      it("should call tools") {
        val result = initialized()((_, h) =>
          messages(h, plain(2, "tools/call", "name" -> "echo".asJson, "arguments" -> json"""{"text": "hi"}"""))
        ).result
        result shouldBe json"""{"content": [{"type": "text", "text": "hi"}]}""".asObject.get
      }

      it("should not send structured content that is not an object") {
        val result = initialized()((_, h) => messages(h, plain(2, "tools/call", "name" -> "arrays".asJson))).result
        result.contains("structuredContent") shouldBe false
        result("content") shouldBe Some(json"""[{"type": "text", "text": "[1,2]"}]""")
      }

      it("should keep structured content that is an object") {
        val result = initialized()((_, h) =>
          messages(h, plain(2, "tools/call", "name" -> "add".asJson, "arguments" -> json"""{"a": 1, "b": 2}"""))
        ).result
        result("structuredContent") shouldBe Some(json"""{"total": 3}""")
      }

      it("should read resources without cache fields") {
        val result = initialized()((_, h) => messages(h, plain(2, "resources/read", "uri" -> "test://a".asJson))).result
        result.keys.toSet shouldBe Set("contents")
      }

      it("should answer with the old error code for resources that do not exist") {
        val error = initialized()((_, h) => messages(h, plain(2, "resources/read", "uri" -> "test://z".asJson))).error
        error.code shouldBe McpErrorCode.LegacyResourceNotFound
      }

      it("should reject unknown methods and the methods of the new version") {
        initialized()((_, h) => messages(h, plain(2, "does/not/exist"))).error.code shouldBe ErrorCode.MethodNotFound
        initialized()((_, h) => messages(h, plain(2, "server/discover"))).error.code shouldBe ErrorCode.MethodNotFound
        initialized()((_, h) => messages(h, plain(2, "subscriptions/listen", "notifications" -> json"{}"))).error.code shouldBe
          ErrorCode.MethodNotFound
      }

      it("should send progress") {
        val all = initialized()((_, h) =>
          messages(
            h,
            plain(
              2,
              "tools/call",
              "name" -> "progress".asJson,
              "arguments" -> json"""{"text": "x"}""",
              "_meta" -> json"""{"progressToken": 5}""",
            ),
          )
        )
        all.notifications.map(_.method) shouldBe List("notifications/progress", "notifications/progress")
        all.notifications.head.params.value("progressToken") shouldBe Some(5.asJson)
      }

      it("should complete a list of prompts and completions as before") {
        val prompts = initialized()((_, h) => messages(h, plain(2, "prompts/list"))).result
        prompts.keys.toSet shouldBe Set("prompts")
        val completion = initialized()((_, h) =>
          messages(
            h,
            plain(
              3,
              "completion/complete",
              "ref" -> json"""{"type": "ref/prompt", "name": "greet"}""",
              "argument" -> json"""{"name": "name", "value": "b"}""",
            ),
          )
        ).result
        completion("completion") shouldBe Some(json"""{"values": ["bob"]}""")
      }
    }

    describe("icons") {
      def iconic(version: String): JsonObject =
        initialized(version)((_, h) => messages(h, plain(2, "tools/list"))).result("tools").value.asArray.value
          .find(_.hcursor.get[String]("name") == Right("iconic")).value.asObject.value

      it("should be left out for 2025-06-18") {
        iconic("2025-06-18").contains("icons") shouldBe false
      }

      it("should be sent for 2025-11-25") {
        iconic("2025-11-25")("icons").value.asArray.value.size shouldBe 1
      }

      it("should not be in the server info of 2025-06-18") {
        val info = withSession((_, h) => messages(h, initializeRequest(version = "2025-06-18"))).result("serverInfo").value
        info.asObject.value.contains("icons") shouldBe false
      }
    }

    describe("input required") {
      it("should ask the user with a request to the client and complete the original request") {
        val all = initialized()((_, h) => drive(h, plain(2, "tools/call", "name" -> "ask".asJson), accept("Ada")))
        val ask = all.head.asInstanceOf[JsonRpc.Request]
        ask.method shouldBe "elicitation/create"
        ask.params.value("message") shouldBe Some("Who are you?".asJson)
        ask.params.value("requestedSchema").value.hcursor.downField("required").as[List[String]].value shouldBe List("name")
        ask.params.value.contains("mode") shouldBe false
        all.result("content") shouldBe Some(json"""[{"type": "text", "text": "hello Ada (asked)"}]""")
        all.result.contains("resultType") shouldBe false
      }

      it("should pass on a declined elicitation") {
        val all = initialized()((_, h) =>
          drive(
            h,
            plain(2, "tools/call", "name" -> "ask".asJson),
            r => JsonRpc.Response.Success(r.id, json"""{"action": "decline"}""".asObject.get),
          )
        )
        all.result("content") shouldBe Some(json"""[{"type": "text", "text": "declined"}]""")
      }

      it("should treat an error of the client as cancelled") {
        val all = initialized()((_, h) =>
          drive(
            h,
            plain(2, "tools/call", "name" -> "ask".asJson),
            r => JsonRpc.Response.Error(r.id, ErrorCode.InternalError, "failed", None),
          )
        )
        all.result("content") shouldBe Some(json"""[{"type": "text", "text": "declined"}]""")
      }

      it("should fail when the client cannot be asked") {
        val all = initialized(capabilities = json"{}")((_, h) =>
          drive(h, plain(2, "tools/call", "name" -> "ask".asJson), accept("Ada"))
        )
        all.error.code shouldBe McpErrorCode.MissingRequiredClientCapability
      }

      it("should stop asking after the configured number of rounds") {
        val all = initialized(config = McpServerConfig(maxLegacyInputRounds = 2))((_, h) =>
          drive(
            h,
            plain(2, "tools/call", "name" -> "needy".asJson),
            r => JsonRpc.Response.Success(r.id, json"""{"action": "accept", "content": {"yes": true}}""".asObject.get),
          )
        )
        all.collect { case r: JsonRpc.Request => r }.size shouldBe 2
        all.error.code shouldBe ErrorCode.InternalError
      }

      it("should stop waiting when the stream is cancelled") {
        val program = initialized()((_, h) =>
          for
            started <- Deferred[IO, Unit]
            fiber <- h.request(plain(2, "tools/call", "name" -> "ask".asJson), context())
              .evalTap(_ => started.complete(()).void).compile.toList.start
            _ <- started.get
            _ <- fiber.cancel
            outcome <- fiber.join
          yield outcome.isCanceled
        )
        program shouldBe true
      }
    }

    describe("cancellation") {
      it("should cancel a request when the client cancels it") {
        val result = initialized()((server, h) =>
          for
            fiber <- h.request(plain(2, "tools/call", "name" -> "slow".asJson, "arguments" -> json"""{"text": "x"}"""), context())
              .compile.toList.start
            _ <- server.started.get
            _ <- h.notification(
              JsonRpc.Notification("notifications/cancelled", Some(JsonObject("requestId" -> 2.asJson))),
              context(),
            )
            _ <- server.cancelled.get.timeout(5.seconds)
            messages <- fiber.joinWithNever
          yield messages
        )
        result shouldBe empty
      }
    }

    describe("notifications of the server") {
      it("should send the changes of the tools after the handshake") {
        val notification = initialized()((server, h) =>
          for
            fiber <- h.unsolicited.take(1).compile.toList.start
            _ <- IO.sleep(300.millis)
            _ <- server.changes.publish1(())
            messages <- fiber.joinWithNever
          yield messages.head
        )
        notification shouldBe JsonRpc.Notification("notifications/tools/list_changed", None)
      }

      it("should send the updates of the resources that the client subscribed to") {
        val notifications = initialized()((server, h) =>
          for
            subscribed <- messages(h, plain(2, "resources/subscribe", "uri" -> "test://a".asJson))
            fiber <- h.unsolicited.take(1).compile.toList.start
            _ <- IO.sleep(300.millis)
            _ <- server.updates.publish1("test://b")
            _ <- server.updates.publish1("test://a")
            messages <- fiber.joinWithNever
          yield subscribed.result -> messages
        )
        notifications._1 shouldBe JsonObject.empty
        notifications._2 shouldBe List(
          JsonRpc.Notification("notifications/resources/updated", Some(JsonObject("uri" -> "test://a".asJson)))
        )
      }

      it("should stop the updates after unsubscribe") {
        val result = initialized()((server, h) =>
          for
            _ <- messages(h, plain(2, "resources/subscribe", "uri" -> "test://a".asJson))
            unsubscribed <- messages(h, plain(3, "resources/unsubscribe", "uri" -> "test://a".asJson))
            fiber <- h.unsolicited.take(1).compile.toList.start
            _ <- IO.sleep(300.millis)
            _ <- server.updates.publish1("test://a")
            received <- fiber.join.timeout(500.millis).attempt
          yield unsubscribed.result -> received
        )
        result._1 shouldBe JsonObject.empty
        result._2.isLeft shouldBe true
      }

      it("should reject subscriptions before the handshake") {
        withSession((_, h) => messages(h, plain(1, "resources/subscribe", "uri" -> "test://a".asJson))).error.code shouldBe
          ErrorCode.InvalidRequest
      }

      it("should reject subscriptions when the server does not support them") {
        val bare = new McpServer[IO] with ToolProvider[IO]:
          override val serverInfo: Implementation = Implementation("bare", "1")
          override def instructions: IO[Option[String]] = IO.pure(None)
          override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] = IO.pure(Nil)
        val error = bare.handlerFactory(McpServerConfig(), _ => IO.unit).connection(httpInfo).use(h =>
          messages(h, initializeRequest()) >> messages(h, plain(2, "resources/subscribe", "uri" -> "test://a".asJson))
        ).run.error
        error.code shouldBe ErrorCode.MethodNotFound
      }
    }

    describe("the stdio connection (both generations)") {
      val stdio = JsonRpcConnection.Info.Stdio(Map.empty)

      it("should serve a modern client without handshake") {
        val result = withSession((_, h) => messages(h, request(1, "tools/list")), info = stdio).result
        result("resultType") shouldBe Some("complete".asJson)
      }

      it("should answer pings before the handshake") {
        withSession((_, h) => messages(h, plain(1, "ping")), info = stdio).result shouldBe JsonObject.empty
      }

      it("should serve a legacy client after the handshake") {
        val result = initialized(info = stdio)((_, h) => messages(h, plain(2, "tools/list"))).result
        result.keys.toSet shouldBe Set("tools")
      }

      it("should drive the input of a legacy client") {
        val all = initialized(info = stdio)((_, h) => drive(h, plain(2, "tools/call", "name" -> "ask".asJson), accept("Bo")))
        all.result("content").value.noSpaces should include("hello Bo")
      }

      it("should send the changes to a legacy client only after the handshake") {
        val result = withSession(
          (server, h) =>
            for
              fiber <- h.unsolicited.take(1).compile.toList.start
              _ <- IO.sleep(200.millis)
              _ <- server.changes.publish1(()) // nobody listens yet
              early <- fiber.join.timeout(300.millis).attempt
              _ <- messages(h, initializeRequest())
              _ <- IO.sleep(300.millis)
              _ <- server.changes.publish1(())
              late <- fiber.joinWithNever
            yield early.isLeft -> late.size,
          info = stdio,
        )
        result shouldBe (true -> 1)
      }

      it("should know the cancellation of both generations") {
        val handler = withSession((_, h) => IO.pure(h), info = stdio)
        handler.cancelledRequest(JsonRpc.Notification("notifications/cancelled", Some(JsonObject("requestId" -> 4.asJson)))) shouldBe
          Some(JsonRpc.Id.IdInt(4))
      }
    }

    describe("the configuration") {
      it("should not serve legacy clients when disabled") {
        val config = McpServerConfig(supportLegacyClients = false)
        withSession((_, h) => messages(h, initializeRequest()), config).error.code shouldBe ErrorCode.MethodNotFound
        withSession(
          (_, h) => messages(h, initializeRequest()),
          config,
          JsonRpcConnection.Info.Stdio(Map.empty),
        ).error.code shouldBe ErrorCode.MethodNotFound
      }

      it("should tell if sessions are supported") {
        val factory = (config: McpServerConfig) =>
          FixtureServer.create.map(_.handlerFactory(config, _ => IO.unit)).run: JsonRpcHandlerFactory[IO]
        factory(McpServerConfig()).supportsSessions shouldBe true
        factory(McpServerConfig(supportLegacyClients = false)).supportsSessions shouldBe false
      }

      it("should list the versions in discover and in the unsupported version error") {
        val discover = withSession((_, h) => messages(h, request(1, "server/discover")), info = JsonRpcConnection.Info.Stdio(Map.empty)).result
        discover("supportedVersions") shouldBe Some(json"""["2026-07-28", "2025-11-25", "2025-06-18"]""")
        val error = withSession(
          (_, h) => messages(h, requestWithMeta(1, "tools/list", clientMeta(version = "1900-01-01"))),
          info = JsonRpcConnection.Info.Stdio(Map.empty),
        ).error
        error.data.value.as[UnsupportedProtocolVersionData].value.supported shouldBe
          List("2026-07-28", "2025-11-25", "2025-06-18")
      }

      it("should use the authentication of the requests") {
        val result = withSession((_, h) =>
          for
            _ <- messages(h, initializeRequest())
            r <- messages(h, plain(2, "tools/list"), Authentication.BearerToken("admin"))
          yield r.result
        )
        result("tools").value.asArray.value.size shouldBe 12
      }
    }
  }
