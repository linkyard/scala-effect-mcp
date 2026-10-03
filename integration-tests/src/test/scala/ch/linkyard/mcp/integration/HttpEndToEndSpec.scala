package ch.linkyard.mcp.integration

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.transport.http4s.McpServerRoute
import ch.linkyard.mcp.jsonrpc2.transport.http4s.McpServerRouteConfig
import ch.linkyard.mcp.jsonrpc2.transport.http4s.SessionStore
import ch.linkyard.mcp.server.McpServer.*
import ch.linkyard.mcp.server.McpServerConfig
import fs2.Stream
import fs2.text
import io.circe.Json
import io.circe.literal.*
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString
import org.typelevel.ci.CIStringSyntax

import scala.concurrent.duration.DurationInt

/** The server behind the http routes, as a client would call them. */
class HttpEndToEndSpec extends AnyFunSpec with Matchers with OptionValues with EitherValues:
  private val Version = "2026-07-28"
  private val host = headers.Host("mcp.example.com")
  private val modernMeta = json"""{
    "io.modelcontextprotocol/protocolVersion": "2026-07-28",
    "io.modelcontextprotocol/clientCapabilities": {"elicitation": {}}
  }"""

  private class Calls(val route: HttpRoutes[IO], val server: E2eServer):
    def run(request: Request[IO]): IO[Response[IO]] = route.orNotFound.run(request)

    def post(body: Json, headers: (String, String)*): Request[IO] =
      Request[IO](Method.POST, uri"/mcp")
        .putHeaders(host, org.http4s.headers.Accept(MediaType.application.json, MediaType.`text/event-stream`))
        .putHeaders(headers.map((k, v) => Header.Raw(CIString(k), v))*)
        .withEntity(body)

    def modern(
      id: Int,
      method: String,
      params: Json = json"{}",
      name: Option[String] = None,
      meta: Json = modernMeta,
    ): Request[IO] =
      post(
        rpc(id, method, params.deepMerge(Json.obj("_meta" -> meta))),
        List("MCP-Protocol-Version" -> Version, "Mcp-Method" -> method) ++ name.map("Mcp-Name" -> _)*
      )

    def legacy(id: Int, method: String, params: Json, session: Option[String]): Request[IO] =
      post(rpc(id, method, params), session.map("Mcp-Session-Id" -> _).toList*)

  private def rpc(id: Int, method: String, params: Json): Json =
    Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id.asJson, "method" -> method.asJson, "params" -> params)

  private def events(response: Response[IO]): Stream[IO, Json] =
    response.body.through(text.utf8.decode).through(text.lines).filter(_.startsWith("data:"))
      .map(line => parse(line.drop(5).trim).value)

  private def withHttp[A](config: McpServerConfig = McpServerConfig())(test: Calls => IO[A]): A =
    (for
      server <- E2eServer.create
      store <- SessionStore.inMemory[IO](1.minute).use(store =>
        val factory = server.handlerFactory(config, e => IO.println(s"server error: $e"))
        val route = McpServerRoute.route(factory, McpServerRouteConfig(keepAliveInterval = 10.seconds))(using store)
        test(Calls(route, server))
      )
    yield store).timeout(30.seconds).unsafeRunSync()

  private def contentType(response: Response[IO]): Option[String] =
    response.headers.get(ci"Content-Type").map(_.head.value.takeWhile(_ != ';'))

  describe("The http transport") {
    describe("with a client of 2026-07-28") {
      it("should answer a request with json") {
        val (status, mediaType, body) = withHttp() { h =>
          for
            response <-
              h.run(h.modern(1, "tools/call", json"""{"name": "echo", "arguments": {"text": "hi"}}""", Some("echo")))
            body <- response.as[Json]
          yield (response.status, contentType(response), body)
        }
        status shouldBe Status.Ok
        mediaType shouldBe Some("application/json")
        body.hcursor.downField(
          "result"
        ).downField("content").focus.value shouldBe json"""[{"type": "text", "text": "hi"}]"""
        body.hcursor.get[Int]("id").value shouldBe 1
      }

      it("should not open a session") {
        val response = withHttp()(h => h.run(h.modern(1, "server/discover")))
        response.headers.get(ci"Mcp-Session-Id") shouldBe None
      }

      it("should stream the progress and the result when the client wants progress") {
        val (mediaType, messages) = withHttp() { h =>
          val meta = modernMeta.deepMerge(json"""{"progressToken": 7}""")
          for
            response <- h.run(h.modern(
              1,
              "tools/call",
              json"""{"name": "progress", "arguments": {"text": "x"}}""",
              Some("progress"),
              meta,
            ))
            messages <- events(response).compile.toList
          yield (contentType(response), messages)
        }
        mediaType shouldBe Some("text/event-stream")
        messages.map(_.hcursor.get[String]("method").toOption) shouldBe
          List(Some("notifications/progress"), Some("notifications/progress"), None)
        messages.last.hcursor.downField("result").get[String]("resultType").value shouldBe "complete"
      }

      it("should reject requests whose headers do not match the body") {
        val (status, body) = withHttp() { h =>
          for
            response <-
              h.run(h.modern(1, "tools/call", json"""{"name": "echo", "arguments": {"text": "hi"}}""", Some("other")))
            body <- response.as[Json]
          yield (response.status, body)
        }
        status shouldBe Status.BadRequest
        body.hcursor.downField("error").get[Int]("code").value shouldBe -32020
      }

      it("should reject a request without the protocol version") {
        val (status, body) = withHttp() { h =>
          val request = h.post(rpc(1, "tools/list", json"{}"), "Mcp-Method" -> "tools/list")
          h.run(request).flatMap(r => r.as[Json].map(r.status -> _))
        }
        status shouldBe Status.BadRequest
        body.hcursor.downField("error").get[Int]("code").value shouldBe -32020
      }

      it("should answer a request without the protocol version in the body with -32602") {
        val (status, body) = withHttp() { h =>
          h.run(h.modern(1, "tools/list", meta = json"""{"io.modelcontextprotocol/clientCapabilities": {}}"""))
            .flatMap(r => r.as[Json].map(r.status -> _))
        }
        status shouldBe Status.BadRequest
        body.hcursor.downField("error").get[Int]("code").value shouldBe -32602
      }

      it("should answer initialize of a current client like a removed method") {
        val (status, body) = withHttp() { h =>
          h.run(h.modern(1, "initialize", json"""{"protocolVersion": "2025-11-25", "capabilities": {}}"""))
            .flatMap(r => r.as[Json].map(r.status -> _))
        }
        status shouldBe Status.NotFound
        body.hcursor.downField("error").get[Int]("code").value shouldBe -32601
      }

      it("should validate the Mcp-Param headers of tools with x-mcp-header annotations") {
        def call(headers: (String, String)*)(region: String) = withHttp() { h =>
          val base = h.modern(
            1,
            "tools/call",
            Json.obj("name" -> "regional".asJson, "arguments" -> Json.obj("region" -> region.asJson)),
            Some("regional"),
          )
          h.run(base.putHeaders(headers.map((k, v) => Header.Raw(CIString(k), v))*))
            .flatMap(r => r.as[Json].map(r.status -> _))
        }
        val (okStatus, ok) = call("Mcp-Param-Region" -> "us-west1")("us-west1")
        okStatus shouldBe Status.Ok
        ok.hcursor.downField("result").downField("content").focus.value shouldBe
          json"""[{"type": "text", "text": "us-west1"}]"""
        for (headers, region) <- List(
            Nil -> "us-west1",
            List("Mcp-Param-Region" -> "eu-west1") -> "us-west1",
            List("Mcp-Param-Region" -> "=?base64?SGVsbG8?=") -> "Hello",
          )
        do
          val (status, body) = call(headers*)(region)
          status shouldBe Status.BadRequest
          body.hcursor.downField("error").get[Int]("code").value shouldBe -32020
      }

      it("should answer 400 for invalid params and 404 for unknown methods") {
        val (invalid, unknown) = withHttp() { h =>
          for
            invalid <- h.run(h.modern(1, "tools/list", meta = json"{}")).map(_.status)
            unknown <- h.run(h.modern(2, "does/not/exist")).map(_.status)
          yield (invalid, unknown)
        }
        invalid shouldBe Status.BadRequest
        unknown shouldBe Status.NotFound
      }

      it("should answer 400 for an unsupported version and list the supported ones") {
        val (status, body) = withHttp() { h =>
          val request = h.post(
            rpc(
              1,
              "tools/list",
              json"""{"_meta": {"io.modelcontextprotocol/protocolVersion": "1999-01-01", "io.modelcontextprotocol/clientCapabilities": {}}}""",
            ),
            "MCP-Protocol-Version" -> "1999-01-01",
            "Mcp-Method" -> "tools/list",
          )
          h.run(request).flatMap(r => r.as[Json].map(r.status -> _))
        }
        status shouldBe Status.BadRequest
        body.hcursor.downField("error").downField("data").get[List[String]]("supported").value shouldBe
          List("2026-07-28", "2025-11-25", "2025-06-18")
      }

      it("should retry a request that needs input") {
        val body = withHttp() { h =>
          for
            first <- h.run(h.modern(
              1,
              "tools/call",
              json"""{"name": "ask", "arguments": {"text": "me"}}""",
              Some("ask"),
            )).flatMap(_.as[Json])
            state = first.hcursor.downField("result").get[String]("requestState").value
            params = Json.obj(
              "name" -> "ask".asJson,
              "arguments" -> json"""{"text": "me"}""",
              "requestState" -> state.asJson,
              "inputResponses" -> json"""{"name": {"action": "accept", "content": {"name": "Ada"}}}""",
            )
            second <- h.run(h.modern(2, "tools/call", params, Some("ask"))).flatMap(_.as[Json])
          yield second
        }
        body.hcursor.downField(
          "result"
        ).downField("content").focus.value shouldBe json"""[{"type": "text", "text": "hello Ada"}]"""
      }

      it("should stream the notifications of a subscription") {
        val messages = withHttp() { h =>
          for
            response <-
              h.run(h.modern(1, "subscriptions/listen", json"""{"notifications": {"toolsListChanged": true}}"""))
            collected <- events(response).take(2).compile.toList.start
            _ <- IO.sleep(300.millis)
            _ <- h.server.changes.publish1(())
            messages <- collected.joinWithNever
          yield messages
        }
        messages.map(_.hcursor.get[String]("method").value) shouldBe
          List("notifications/subscriptions/acknowledged", "notifications/tools/list_changed")
      }

      it("should cancel the request when the client disconnects") {
        withHttp() { h =>
          for
            fiber <- h.run(h.modern(1, "tools/call", json"""{"name": "slow", "arguments": {"text": "x"}}""", Some("slow")))
              .flatMap(_.as[Json]).start
            _ <- h.server.slowStarted.get
            _ <- fiber.cancel
            _ <- h.server.slowCancelled.get.timeout(5.seconds)
          yield ()
        }
      }

      it("should not accept GET and DELETE") {
        val (get, delete) = withHttp(McpServerConfig(supportLegacyClients = false)) { h =>
          for
            get <- h.run(Request[IO](Method.GET, uri"/mcp").putHeaders(host)).map(_.status)
            delete <- h.run(Request[IO](Method.DELETE, uri"/mcp").putHeaders(host)).map(_.status)
          yield (get, delete)
        }
        get shouldBe Status.MethodNotAllowed
        delete shouldBe Status.MethodNotAllowed
      }

      it("should ignore a session id when sessions are not supported") {
        val response = withHttp(McpServerConfig(supportLegacyClients = false)) { h =>
          h.run(h.modern(1, "tools/list").putHeaders(Header.Raw(ci"Mcp-Session-Id", "abc")))
        }
        response.status shouldBe Status.Ok
        response.headers.get(ci"Mcp-Session-Id") shouldBe None
      }

      it("should not accept requests from foreign origins") {
        val status = withHttp() { h =>
          h.run(h.modern(1, "tools/list").putHeaders(Header.Raw(ci"Origin", "https://evil.example.org"))).map(_.status)
        }
        status shouldBe Status.Forbidden
      }

      it("should not accept an origin that has the host of the request (DNS rebinding)") {
        val status = withHttp() { h =>
          h.run(h.modern(1, "tools/list").putHeaders(Header.Raw(ci"Origin", "http://mcp.example.com"))).map(_.status)
        }
        status shouldBe Status.Forbidden
      }
    }

    describe("with a client of an earlier version") {
      def initialize(h: Calls, version: String = "2025-06-18"): IO[(Json, String)] =
        for
          response <- h.run(h.legacy(
            1,
            "initialize",
            json"""{"protocolVersion": $version, "capabilities": {"elicitation": {}}, "clientInfo": {"name": "old", "version": "1"}}""",
            None,
          ))
          body <- response.as[Json]
          session = response.headers.get(ci"Mcp-Session-Id").map(_.head.value).value
          initialized <- h.run(
            h.post(
              json"""{"jsonrpc": "2.0", "method": "notifications/initialized"}""",
              "Mcp-Session-Id" -> session,
            )
          )
          _ = initialized.status shouldBe Status.Accepted
        yield body -> session

      it("should open a session with the handshake") {
        val (body, session) = withHttp()(h => initialize(h))
        body.hcursor.downField("result").get[String]("protocolVersion").value shouldBe "2025-06-18"
        session should not be empty
      }

      it("should serve the requests of the session") {
        val body = withHttp() { h =>
          for
            (_, session) <- initialize(h)
            response <-
              h.run(h.legacy(2, "tools/call", json"""{"name": "echo", "arguments": {"text": "hi"}}""", Some(session)))
            body <- response.as[Json]
          yield body
        }
        body.hcursor.downField("result").keys.value.toSet shouldBe Set("content")
      }

      it("should ask the user with a request in the stream of the request") {
        val (question, result) = withHttp() { h =>
          for
            (_, session) <- initialize(h)
            response <-
              h.run(h.legacy(2, "tools/call", json"""{"name": "ask", "arguments": {"text": "me"}}""", Some(session)))
            queue <- Queue.unbounded[IO, Json]
            reader <- events(response).evalMap(queue.offer).compile.drain.start
            question <- queue.take.timeout(5.seconds)
            answer <- h.run(h.post(
              Json.obj(
                "jsonrpc" -> "2.0".asJson,
                "id" -> question.hcursor.get[Json]("id").value,
                "result" -> json"""{"action": "accept", "content": {"name": "Bo"}}""",
              ),
              "Mcp-Session-Id" -> session,
            ))
            _ = answer.status shouldBe Status.Accepted
            result <- queue.take.timeout(5.seconds)
            _ <- reader.joinWithNever
          yield question -> result
        }
        question.hcursor.get[String]("method").value shouldBe "elicitation/create"
        result.hcursor.downField(
          "result"
        ).downField("content").focus.value shouldBe json"""[{"type": "text", "text": "hello Bo"}]"""
      }

      it("should stream the changes on the get stream of the session") {
        val notification = withHttp() { h =>
          for
            (_, session) <- initialize(h)
            response <- h.run(Request[IO](Method.GET, uri"/mcp").putHeaders(host, Header.Raw(ci"Mcp-Session-Id", session)))
            collected <- events(response).take(1).compile.toList.start
            _ <- IO.sleep(300.millis)
            _ <- h.server.changes.publish1(())
            messages <- collected.joinWithNever
          yield messages.head
        }
        notification.hcursor.get[String]("method").value shouldBe "notifications/tools/list_changed"
      }

      it("should close the session with delete") {
        val (deleted, after) = withHttp() { h =>
          for
            (_, session) <- initialize(h)
            deleted <- h.run(Request[IO](
              Method.DELETE,
              uri"/mcp",
            ).putHeaders(host, Header.Raw(ci"Mcp-Session-Id", session))).map(_.status)
            after <- h.run(h.legacy(2, "tools/list", json"{}", Some(session))).map(_.status)
          yield (deleted, after)
        }
        deleted shouldBe Status.NoContent
        after shouldBe Status.NotFound
      }

      it("should reject an unknown session") {
        withHttp()(h =>
          h.run(h.legacy(2, "tools/list", json"{}", Some("0" * 32))).map(_.status)
        ) shouldBe Status.NotFound
      }

      it("should not open sessions when legacy clients are disabled") {
        val (status, body) = withHttp(McpServerConfig(supportLegacyClients = false)) { h =>
          h.run(h.legacy(
            1,
            "initialize",
            json"""{"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "old", "version": "1"}}""",
            None,
          )).flatMap(r => r.as[Json].map(r.status -> _))
        }
        status shouldBe Status.BadRequest
        body.hcursor.downField("error").get[String]("message").value should include("protocol version")
      }
    }
  }
