package ch.linkyard.mcp.integration

import cats.effect.IO
import cats.effect.kernel.Fiber
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.transport.LineBasedJsonRpcConnection
import ch.linkyard.mcp.server.McpServer.*
import ch.linkyard.mcp.server.McpServerConfig
import fs2.Pipe
import fs2.Stream
import fs2.text
import io.circe.Json
import io.circe.literal.*
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

/** The server with the line based framing of the stdio transport, as a client would use it. */
class StdioEndToEndSpec extends AnyFunSpec with Matchers with OptionValues with EitherValues:
  private val modernMeta = json"""{
    "io.modelcontextprotocol/protocolVersion": "2026-07-28",
    "io.modelcontextprotocol/clientCapabilities": {"elicitation": {}}
  }"""

  /** The client side of the connection: what is written is read by the server as a line. */
  private class Client(
    in: Queue[IO, Option[String]],
    out: Queue[IO, String],
    val server: E2eServer,
    private val running: Fiber[IO, Throwable, Unit],
  ):
    def send(message: Json): IO[Unit] = in.offer(Some(message.noSpaces + "\n"))
    def receive: IO[Json] = out.take.timeout(5.seconds).map(parse(_).value)
    def receive(count: Int): IO[List[Json]] = List.fill(count)(receive).sequence
    def noMessage: IO[Boolean] = out.take.timeout(300.millis).attempt.map(_.isLeft)
    def closeInput: IO[Unit] = in.offer(None)
    def finished: IO[Unit] = running.joinWithNever.timeout(5.seconds)

    def request(id: Int, method: String, params: Json = json"{}", meta: Option[Json] = Some(modernMeta)): IO[Unit] =
      val withMeta = meta.fold(params)(m => params.deepMerge(Json.obj("_meta" -> m)))
      send(Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id.asJson, "method" -> method.asJson, "params" -> withMeta))

    def notify(method: String, params: Json = json"{}"): IO[Unit] =
      send(Json.obj("jsonrpc" -> "2.0".asJson, "method" -> method.asJson, "params" -> params))

  private def withClient[A](config: McpServerConfig = McpServerConfig())(test: Client => IO[A]): A =
    (for
      server <- E2eServer.create
      in <- Queue.unbounded[IO, Option[String]]
      out <- Queue.unbounded[IO, String]
      input = Stream.fromQueueNoneTerminated(in).through(text.utf8.encode[IO])
      output: Pipe[IO, Byte, Unit] = _.through(text.utf8.decode).through(text.lines).filter(_.nonEmpty).evalMap(out.offer)
      connection = LineBasedJsonRpcConnection[IO](input, output, JsonRpcConnection.Info.Stdio(Map.empty))
      running <- server.run(connection, e => IO.println(s"server error: $e"), config).start
      result <- test(Client(in, out, server, running))
    yield result).timeout(30.seconds).unsafeRunSync()

  describe("The stdio transport") {
    describe("with a client of 2026-07-28") {
      it("should serve requests without a handshake") {
        val (discover, tools, called) = withClient() { c =>
          for
            _ <- c.request(1, "server/discover")
            discover <- c.receive
            _ <- c.request(2, "tools/list")
            tools <- c.receive
            _ <- c.request(3, "tools/call", json"""{"name": "echo", "arguments": {"text": "hi"}}""")
            called <- c.receive
          yield (discover, tools, called)
        }
        discover.hcursor.downField("result").get[List[String]]("supportedVersions").value shouldBe
          List("2026-07-28", "2025-11-25", "2025-06-18")
        tools.hcursor.downField("result").downField("tools").as[List[Json]].value.map(_.hcursor.get[String]("name").value) shouldBe
          List("ask", "echo", "progress", "slow")
        called.hcursor.downField("result").downField("content").focus.value shouldBe
          json"""[{"type": "text", "text": "hi"}]"""
      }

      it("should send the progress before the result") {
        val messages = withClient() { c =>
          c.request(
            1,
            "tools/call",
            json"""{"name": "progress", "arguments": {"text": "x"}}""",
            Some(modernMeta.deepMerge(json"""{"progressToken": "p"}""")),
          ) >> c.receive(3)
        }
        messages.map(_.hcursor.get[String]("method").toOption) shouldBe
          List(Some("notifications/progress"), Some("notifications/progress"), None)
        messages.last.hcursor.get[Int]("id").value shouldBe 1
      }

      it("should answer requests concurrently and cancel on request") {
        val (answer, cancelled) = withClient() { c =>
          for
            _ <- c.request(1, "tools/call", json"""{"name": "slow", "arguments": {"text": "x"}}""")
            _ <- c.server.slowStarted.get
            _ <- c.request(2, "tools/call", json"""{"name": "echo", "arguments": {"text": "quick"}}""")
            answer <- c.receive
            _ <- c.notify("notifications/cancelled", json"""{"requestId": 1}""")
            _ <- c.server.slowCancelled.get.timeout(5.seconds)
            noReply <- c.noMessage
          yield (answer.hcursor.get[Int]("id").value, noReply)
        }
        answer shouldBe 2
        cancelled shouldBe true // no response for the cancelled request
      }

      it("should report errors of the request") {
        val (invalid, unknown, version) = withClient() { c =>
          for
            _ <- c.request(1, "tools/list", meta = None)
            invalid <- c.receive
            _ <- c.request(2, "nope")
            unknown <- c.receive
            _ <- c.request(3, "tools/list", meta = Some(json"""{
              "io.modelcontextprotocol/protocolVersion": "1999-01-01",
              "io.modelcontextprotocol/clientCapabilities": {}
            }"""))
            version <- c.receive
          yield (invalid, unknown, version)
        }
        invalid.hcursor.downField("error").get[Int]("code").value shouldBe -32602
        unknown.hcursor.downField("error").get[Int]("code").value shouldBe -32601
        version.hcursor.downField("error").get[Int]("code").value shouldBe -32022
      }

      it("should ask the user with an input required result") {
        val (first, second) = withClient() { c =>
          for
            _ <- c.request(1, "tools/call", json"""{"name": "ask", "arguments": {"text": "me"}}""")
            first <- c.receive
            state = first.hcursor.downField("result").get[String]("requestState").value
            _ <- c.request(
              2,
              "tools/call",
              Json.obj(
                "name" -> "ask".asJson,
                "arguments" -> json"""{"text": "me"}""",
                "requestState" -> state.asJson,
                "inputResponses" -> json"""{"name": {"action": "accept", "content": {"name": "Ada"}}}""",
              ),
            )
            second <- c.receive
          yield (first, second)
        }
        first.hcursor.downField("result").get[String]("resultType").value shouldBe "input_required"
        second.hcursor.downField("result").downField("content").focus.value shouldBe
          json"""[{"type": "text", "text": "hello Ada"}]"""
      }

      it("should send notifications to a subscription") {
        val messages = withClient() { c =>
          for
            _ <- c.request(1, "subscriptions/listen", json"""{"notifications": {"toolsListChanged": true}}""")
            ack <- c.receive
            _ <- IO.sleep(300.millis)
            _ <- c.server.changes.publish1(())
            changed <- c.receive
          yield List(ack, changed)
        }
        messages.map(_.hcursor.get[String]("method").value) shouldBe
          List("notifications/subscriptions/acknowledged", "notifications/tools/list_changed")
        messages.last.hcursor.downField("params").downField("_meta").get[Int]("io.modelcontextprotocol/subscriptionId").value shouldBe 1
      }
    }

    describe("with a client of an earlier version") {
      def initialize(c: Client, version: String = "2025-06-18"): IO[Json] =
        c.request(
          1,
          "initialize",
          json"""{
            "protocolVersion": $version,
            "capabilities": {"elicitation": {}},
            "clientInfo": {"name": "old", "version": "1"}
          }""",
          meta = None,
        ) >> c.receive <* c.notify("notifications/initialized")

      it("should do the handshake") {
        val result = withClient()(c => initialize(c)).hcursor.downField("result")
        result.get[String]("protocolVersion").value shouldBe "2025-06-18"
        result.downField("serverInfo").get[String]("name").value shouldBe "e2e"
        result.downField("capabilities").downField("tools").get[Boolean]("listChanged").value shouldBe true
      }

      it("should serve the requests like the earlier version did") {
        val (ping, tools, called) = withClient() { c =>
          for
            _ <- initialize(c)
            _ <- c.request(2, "ping", meta = None)
            ping <- c.receive
            _ <- c.request(3, "tools/list", meta = None)
            tools <- c.receive
            _ <- c.request(4, "tools/call", json"""{"name": "echo", "arguments": {"text": "hi"}}""", meta = None)
            called <- c.receive
          yield (ping, tools, called)
        }
        ping.hcursor.downField("result").focus.value shouldBe json"{}"
        tools.hcursor.downField("result").keys.value.toList shouldBe List("tools")
        called.hcursor.downField("result").keys.value.toSet shouldBe Set("content")
      }

      it("should ask the user with a request of the server") {
        val (question, result) = withClient() { c =>
          for
            _ <- initialize(c)
            _ <- c.request(2, "tools/call", json"""{"name": "ask", "arguments": {"text": "me"}}""", meta = None)
            question <- c.receive
            _ <- c.send(Json.obj(
              "jsonrpc" -> "2.0".asJson,
              "id" -> question.hcursor.get[Json]("id").value,
              "result" -> json"""{"action": "accept", "content": {"name": "Bo"}}""",
            ))
            result <- c.receive
          yield (question, result)
        }
        question.hcursor.get[String]("method").value shouldBe "elicitation/create"
        question.hcursor.downField("params").get[String]("message").value shouldBe "Name for me?"
        result.hcursor.get[Int]("id").value shouldBe 2
        result.hcursor.downField("result").downField("content").focus.value shouldBe
          json"""[{"type": "text", "text": "hello Bo"}]"""
      }

      it("should send list changes after the handshake") {
        val notification = withClient() { c =>
          for
            _ <- initialize(c, "2025-11-25")
            _ <- IO.sleep(300.millis)
            _ <- c.server.changes.publish1(())
            n <- c.receive
          yield n
        }
        notification.hcursor.get[String]("method").value shouldBe "notifications/tools/list_changed"
      }

      it("should not be served when legacy clients are disabled") {
        val response = withClient(McpServerConfig(supportLegacyClients = false))(c => initialize(c))
        response.hcursor.downField("error").get[Int]("code").value shouldBe -32601
      }
    }

    it("should end when the client closes the input") {
      withClient() { c => c.closeInput >> c.finished }
    }
  }
