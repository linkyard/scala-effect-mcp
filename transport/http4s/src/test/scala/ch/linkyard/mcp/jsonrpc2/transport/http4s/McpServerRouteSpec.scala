package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpc.Id
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIStringSyntax

import scala.concurrent.duration.*

class McpServerRouteSpec extends AnyFunSpec with Matchers:
  private val Version = "2026-07-28"
  private val VersionKey = "io.modelcontextprotocol/protocolVersion"
  private val host = headers.Host("mcp.example.com")

  private def success(id: Long): JsonRpc.Message = JsonRpc.Response.Success(Id.IdInt(id), JsonObject("ok" -> true.asJson))
  private def error(id: Long, code: Int): JsonRpc.Message =
    JsonRpc.Response.Error(Id.IdInt(id), JsonRpc.ErrorCode.fromInt(code), "failed", None)
  private def progress: JsonRpc.Message = JsonRpc.Notification("notifications/progress", None)
  private def respondWith(f: Long => List[JsonRpc.Message]): JsonRpc.Request => Stream[IO, JsonRpc.Message] =
    r =>
      r.id match
        case Id.IdInt(i) => Stream.emits(f(i))
        case _           => Stream.empty
  private val okOnly = respondWith(i => List(success(i)))

  private def requestBody(
    method: String,
    params: JsonObject = JsonObject.empty,
    version: Option[String] = Some(Version),
    id: Long = 1,
  ): Json =
    val meta = version.fold(JsonObject.empty)(v => JsonObject(VersionKey -> v.asJson))
    Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id" -> id.asJson,
      "method" -> method.asJson,
      "params" -> params.add("_meta", meta.asJson).asJson,
    )

  private def post(body: Json, hs: (String, String)*): Request[IO] =
    postRaw(body.noSpaces, hs*)

  private def postRaw(body: String, hs: (String, String)*): Request[IO] =
    Request[IO](Method.POST, uri"/mcp")
      .putHeaders(host)
      .putHeaders(hs.map((k, v) => Header.Raw(org.typelevel.ci.CIString(k), v))*)
      .withEntity(body)

  private def modernHeaders(method: String, name: Option[String] = None, version: String = Version) =
    List("MCP-Protocol-Version" -> version, "Mcp-Method" -> method) ++ name.map("Mcp-Name" -> _)

  private def modern(method: String, params: JsonObject = JsonObject.empty, name: Option[String] = None) =
    post(requestBody(method, params), modernHeaders(method, name)*)

  private case class Fixture(
    factory: FakeFactory,
    store: SessionStore[IO],
    route: HttpRoutes[IO],
    sessionHandler: Option[FakeHandler],
  )

  private def withRoute[A](
    stateless: JsonRpc.Request => Stream[IO, JsonRpc.Message] = okOnly,
    session: Option[JsonRpc.Request => Stream[IO, JsonRpc.Message]] = None,
    sessionUnsolicited: Stream[IO, JsonRpc.Message] = Stream.empty,
    config: McpServerRouteConfig = McpServerRouteConfig.default,
    unsolicited: Stream[IO, JsonRpc.Message] = Stream.empty,
    idleTimeout: FiniteDuration = 1.minute,
  )(test: Fixture => IO[A]): A =
    val io = SessionStore.inMemory[IO](idleTimeout).use { store =>
      for
        statelessHandler <- FakeHandler.create(stateless, unsolicited)
        sessionHandler <- session.traverse(FakeHandler.create(_, sessionUnsolicited))
        factory <- FakeFactory.create(statelessHandler, sessionHandler)
        route = McpServerRoute.route(factory, config)(using store)
        result <- test(Fixture(factory, store, route, sessionHandler))
      yield result
    }
    io.timeout(20.seconds).unsafeRunSync()

  private def run(f: Fixture, req: Request[IO]): IO[Response[IO]] = f.route.orNotFound.run(req)

  private def text(res: Response[IO]): IO[String] = res.bodyText.compile.string

  private def json(res: Response[IO]): IO[Json] = res.as[Json]

  private def errorCode(j: Json): Option[Int] = j.hcursor.downField("error").downField("code").as[Int].toOption

  private def mediaType(res: Response[IO]): Option[String] =
    res.contentType.map(c => s"${c.mediaType.mainType}/${c.mediaType.subType}")

  describe("header validation") {
    it("accepts a valid modern request and calls the stateless handler") {
      withRoute() { f =>
        for
          res <- run(f, modern("ping"))
          body <- json(res)
          calls <- f.factory.statelessHandler.requests.get
        yield
          res.status shouldBe Status.Ok
          body.hcursor.downField("result").downField("ok").as[Boolean] shouldBe Right(true)
          calls.map(_._1.method) shouldBe List("ping")
          calls.head._2.authentication shouldBe Authentication.Anonymous
          calls.head._2.connection shouldBe a[JsonRpcConnection.Info.Http]
      }
    }

    it("rejects a missing protocol version header") {
      withRoute() { f =>
        for
          res <- run(f, post(requestBody("ping"), "Mcp-Method" -> "ping"))
          body <- json(res)
          calls <- f.factory.statelessHandler.requests.get
        yield
          res.status shouldBe Status.BadRequest
          errorCode(body) shouldBe Some(-32020)
          body.hcursor.downField("id").as[Long] shouldBe Right(1L)
          calls shouldBe empty
      }
    }

    it("rejects a mismatching protocol version") {
      withRoute() { f =>
        for
          res <- run(f, post(requestBody("ping"), modernHeaders("ping", version = "2025-11-25")*))
          body <- json(res)
        yield
          res.status shouldBe Status.BadRequest
          errorCode(body) shouldBe Some(-32020)
          body.hcursor.downField("error").downField("message").as[String].toOption.get should include("2025-11-25")
      }
    }

    it("leaves a request without protocol version in the body to the handler, which answers it with -32602") {
      val invalidParams = respondWith(i => List(error(i, -32602)))
      withRoute(stateless = invalidParams) { f =>
        for
          res <- run(f, post(requestBody("ping", version = None), modernHeaders("ping")*))
          body <- json(res)
          calls <- f.factory.statelessHandler.requests.get
        yield
          res.status shouldBe Status.BadRequest
          errorCode(body) shouldBe Some(-32602)
          calls should have size 1
      }
    }

    it("rejects a missing or mismatching method header") {
      withRoute() { f =>
        for
          missing <- run(f, post(requestBody("ping"), "MCP-Protocol-Version" -> Version))
          mismatch <- run(f, post(requestBody("ping"), modernHeaders("tools/list")*))
          mismatchBody <- json(mismatch)
        yield
          missing.status shouldBe Status.BadRequest
          mismatch.status shouldBe Status.BadRequest
          errorCode(mismatchBody) shouldBe Some(-32020)
      }
    }

    it("requires a matching name for tools/call and prompts/get") {
      withRoute() { f =>
        val params = JsonObject("name" -> "weather".asJson)
        for
          tools <- run(f, modern("tools/call", params, Some("weather")))
          prompts <- run(f, modern("prompts/get", params, Some("weather")))
          missing <- run(f, modern("tools/call", params))
          wrong <- run(f, modern("tools/call", params, Some("other")))
          wrongBody <- json(wrong)
        yield
          tools.status shouldBe Status.Ok
          prompts.status shouldBe Status.Ok
          missing.status shouldBe Status.BadRequest
          wrong.status shouldBe Status.BadRequest
          errorCode(wrongBody) shouldBe Some(-32020)
      }
    }

    it("compares the uri of resources/read") {
      withRoute() { f =>
        val params = JsonObject("uri" -> "file:///a.txt".asJson)
        for
          ok <- run(f, modern("resources/read", params, Some("file:///a.txt")))
          wrong <- run(f, modern("resources/read", params, Some("file:///b.txt")))
          missing <- run(f, modern("resources/read", params))
        yield
          ok.status shouldBe Status.Ok
          wrong.status shouldBe Status.BadRequest
          missing.status shouldBe Status.BadRequest
      }
    }

    it("decodes the base64 sentinel of the name") {
      withRoute() { f =>
        val name = "grüsse tool"
        val encoded = s"=?base64?${java.util.Base64.getEncoder.encodeToString(name.getBytes("UTF-8"))}?="
        val params = JsonObject("name" -> name.asJson)
        for
          ok <- run(f, modern("tools/call", params, Some(encoded)))
          wrong <- run(f, modern("tools/call", JsonObject("name" -> "x".asJson), Some(encoded)))
          invalid <- run(f, modern("tools/call", params, Some("=?base64?***?=")))
          unpadded <- run(f, modern("tools/call", JsonObject("name" -> "Hello".asJson), Some("=?base64?SGVsbG8?=")))
        yield
          ok.status shouldBe Status.Ok
          wrong.status shouldBe Status.BadRequest
          invalid.status shouldBe Status.BadRequest
          unpadded.status shouldBe Status.BadRequest // the padding is mandatory
      }
    }

    it("passes the request headers to the handler, by their lower case name, except Authorization") {
      withRoute() { f =>
        val req = modern("tools/call", JsonObject("name" -> "t".asJson), Some("t"))
          .putHeaders(
            Header.Raw(ci"Mcp-Param-Region", "us-west1"),
            Header.Raw(ci"X-Other", "kept"),
            headers.Authorization(Credentials.Token(AuthScheme.Bearer, "secret")),
          )
        for
          res <- run(f, req)
          calls <- f.factory.statelessHandler.requests.get
        yield
          res.status shouldBe Status.Ok
          val passed = calls.head._2.headers.get
          passed("mcp-param-region") shouldBe "us-west1"
          passed("x-other") shouldBe "kept"
          passed should not contain key("authorization")
      }
    }

    it("does not require a name for other methods") {
      withRoute() { f =>
        for res <- run(f, modern("tools/list"))
        yield res.status shouldBe Status.Ok
      }
    }
  }

  describe("answering requests") {
    it("answers with application/json if the first message is the response") {
      withRoute() { f =>
        for res <- run(f, modern("ping"))
        yield
          res.status shouldBe Status.Ok
          mediaType(res) shouldBe Some("application/json")
          res.headers.get(ci"X-Accel-Buffering") shouldBe None
      }
    }

    it("answers with an event stream if there are messages before the response") {
      withRoute(respondWith(i => List(progress, progress, success(i)))) { f =>
        for
          res <- run(f, modern("ping"))
          body <- text(res)
        yield
          res.status shouldBe Status.Ok
          mediaType(res) shouldBe Some("text/event-stream")
          res.headers.get(ci"X-Accel-Buffering").map(_.head.value) shouldBe Some("no")
          val events = body.linesIterator.filter(_.startsWith("data:")).toList
          events should have size 3
          events.take(2).foreach(_ should include("notifications/progress"))
          events.last should include("\"result\"")
          body should not include "id:"
      }
    }

    it("ends the stream after the response") {
      withRoute(respondWith(i => List(progress, success(i), progress))) { f =>
        for
          res <- run(f, modern("ping"))
          body <- text(res)
        yield body.linesIterator.count(_.startsWith("data:")) shouldBe 2
      }
    }

    it("answers subscriptions/listen with an event stream") {
      val ack = JsonRpc.Notification("notifications/subscriptions/acknowledged", None)
      withRoute(_ => Stream.emit(ack) ++ Stream.never[IO]) { f =>
        for
          res <- run(f, modern("subscriptions/listen"))
          first <- res.bodyText.take(1).compile.string
        yield
          mediaType(res) shouldBe Some("text/event-stream")
          first should include("notifications/subscriptions/acknowledged")
      }
    }

    it("answers subscriptions/listen with a stream even if the first message is a response") {
      withRoute(respondWith(i => List(error(i, -32602)))) { f =>
        for res <- run(f, modern("subscriptions/listen"))
        yield mediaType(res) shouldBe Some("text/event-stream")
      }
    }

    it("maps the error codes to status codes") {
      val expected = List(
        -32700 -> Status.BadRequest,
        -32600 -> Status.BadRequest,
        -32602 -> Status.BadRequest,
        -32020 -> Status.BadRequest,
        -32021 -> Status.BadRequest,
        -32022 -> Status.BadRequest,
        -32601 -> Status.NotFound,
        -32603 -> Status.Ok,
        -32000 -> Status.Ok,
        -1 -> Status.Ok,
      )
      expected.foreach { (code, status) =>
        withRoute(respondWith(i => List(error(i, code)))) { f =>
          for
            res <- run(f, modern("ping"))
            body <- json(res)
          yield
            withClue(s"code $code: ") { res.status shouldBe status }
            mediaType(res) shouldBe Some("application/json")
            errorCode(body) shouldBe Some(code)
        }
      }
    }

    it("answers an internal error if the handler fails") {
      withRoute(_ => Stream.raiseError[IO](RuntimeException("boom"))) { f =>
        for
          res <- run(f, modern("ping"))
          body <- json(res)
        yield errorCode(body) shouldBe Some(-32603)
      }
    }
  }

  describe("notifications and responses") {
    val notification = Json.obj("jsonrpc" -> "2.0".asJson, "method" -> "notifications/initialized".asJson)

    it("answers a notification without a session with 202 and an empty body") {
      withRoute() { f =>
        for
          res <- run(f, post(notification))
          body <- text(res)
        yield
          res.status shouldBe Status.Accepted
          body shouldBe ""
      }
    }

    it("rejects a response without a session") {
      val response = Json.obj("jsonrpc" -> "2.0".asJson, "id" -> 5.asJson, "result" -> Json.obj())
      withRoute() { f =>
        for res <- run(f, post(response))
        yield res.status shouldBe Status.BadRequest
      }
    }
  }

  describe("invalid input") {
    it("answers an undecodable body with a parse error") {
      withRoute() { f =>
        for
          res <- run(f, postRaw("{not json"))
          body <- json(res)
        yield
          res.status shouldBe Status.BadRequest
          errorCode(body) shouldBe Some(-32700)
          body.hcursor.downField("id").focus shouldBe Some(Json.Null)
      }
    }

    it("answers a body that is not a json rpc message with a parse error and the id if known") {
      withRoute() { f =>
        for
          res <- run(f, postRaw("""{"id": 7, "foo": 1}"""))
          body <- json(res)
        yield
          res.status shouldBe Status.BadRequest
          errorCode(body) shouldBe Some(-32700)
          body.hcursor.downField("id").as[Long] shouldBe Right(7L)
      }
    }
  }

  describe("origin validation") {
    def withOrigin(origin: String) = modern("ping").putHeaders(Header.Raw(ci"Origin", origin))

    it("accepts requests without origin") {
      withRoute() { f =>
        for res <- run(f, modern("ping"))
        yield res.status shouldBe Status.Ok
      }
    }

    it("rejects an origin that has the same host as the request (DNS rebinding)") {
      withRoute() { f =>
        for
          res <- run(f, withOrigin("https://mcp.example.com"))
          calls <- f.factory.statelessHandler.requests.get
        yield
          res.status shouldBe Status.Forbidden
          calls shouldBe empty
      }
    }

    it("accepts the origin of the request if the configuration allows it") {
      val config = McpServerRouteConfig(originAllowed = _.toString.contains("mcp.example.com"))
      withRoute(config = config) { f =>
        for res <- run(f, withOrigin("https://mcp.example.com"))
        yield res.status shouldBe Status.Ok
      }
    }

    it("accepts localhost by default") {
      withRoute() { f =>
        for
          a <- run(f, withOrigin("http://localhost:3000"))
          b <- run(f, withOrigin("http://127.0.0.1:8080"))
        yield
          a.status shouldBe Status.Ok
          b.status shouldBe Status.Ok
      }
    }

    it("rejects other origins with 403") {
      withRoute() { f =>
        for
          res <- run(f, withOrigin("https://evil.example.org"))
          body <- json(res)
          calls <- f.factory.statelessHandler.requests.get
          nul <- run(f, withOrigin("null"))
          get <-
            run(f, Request[IO](Method.GET, uri"/mcp").putHeaders(host, Header.Raw(ci"Origin", "https://evil.example.org")))
        yield
          res.status shouldBe Status.Forbidden
          body.hcursor.downField("id").focus shouldBe Some(Json.Null)
          calls shouldBe empty
          nul.status shouldBe Status.Forbidden
          get.status shouldBe Status.Forbidden
      }
    }

    it("accepts the origins that the configuration allows") {
      val config = McpServerRouteConfig(originAllowed = _ => true)
      withRoute(config = config) { f =>
        for res <- run(f, withOrigin("https://other.example.org"))
        yield res.status shouldBe Status.Ok
      }
    }
  }

  describe("keep alive and cancellation") {
    it("emits keep alive comments on a stream that stays open, without delaying the messages") {
      val config = McpServerRouteConfig(keepAliveInterval = 100.millis)
      val handler = (_: JsonRpc.Request) => Stream.emit(progress) ++ Stream.never[IO]
      withRoute(handler, config = config) { f =>
        for
          res <- run(f, modern("subscriptions/listen"))
          lines <- res.bodyText.through(fs2.text.lines).filter(_.nonEmpty).take(4).compile.toList
        yield
          lines.head should startWith("data:")
          lines.head should include("notifications/progress")
          lines.tail.foreach(_ should startWith(":"))
      }
    }

    it("stops the keep alive when the stream ends") {
      val config = McpServerRouteConfig(keepAliveInterval = 50.millis)
      withRoute(respondWith(i => List(progress, success(i))), config = config) { f =>
        for
          res <- run(f, modern("ping"))
          body <- res.bodyText.compile.string.timeout(5.seconds)
        yield body.linesIterator.count(_.startsWith("data:")) shouldBe 2
      }
    }

    it("cancels the handler stream if the consumer of the body is cancelled") {
      withRoute(_ => Stream.emit(progress) ++ Stream.never[IO]) { f =>
        for
          finalized <- Deferred[IO, Unit]
          first <- Deferred[IO, Unit]
          handler = (_: JsonRpc.Request) =>
            (Stream.emit(progress) ++ Stream.never[IO]).onFinalize(finalized.complete(()).void)
          factory <- FakeHandler.create(handler).flatMap(FakeFactory.create(_))
          route = McpServerRoute.route(factory)(using f.store)
          res <- route.orNotFound.run(modern("subscriptions/listen"))
          fiber <- res.body.evalTap(_ => first.complete(()).void).compile.drain.start
          _ <- first.get
          _ <- fiber.cancel
          _ <- finalized.get.timeout(5.seconds)
        yield succeed
      }
    }

    it("cancels the handler stream if the request is cancelled before the first message") {
      withRoute() { f =>
        for
          finalized <- Deferred[IO, Unit]
          started <- Deferred[IO, Unit]
          handler = (_: JsonRpc.Request) =>
            (Stream.exec(started.complete(()).void) ++ Stream.never[IO]).onFinalize(finalized.complete(()).void)
          factory <- FakeHandler.create(handler).flatMap(FakeFactory.create(_))
          route = McpServerRoute.route(factory)(using f.store)
          fiber <- route.orNotFound.run(modern("tools/list")).start
          _ <- started.get
          _ <- fiber.cancel
          _ <- finalized.get.timeout(5.seconds)
        yield succeed
      }
    }

    it("releases the handler stream after answering with json") {
      withRoute() { f =>
        for
          finalized <- Deferred[IO, Unit]
          handler = (_: JsonRpc.Request) => Stream.emit(success(1)).onFinalize(finalized.complete(()).void)
          factory <- FakeHandler.create(handler).flatMap(FakeFactory.create(_))
          route = McpServerRoute.route(factory)(using f.store)
          res <- route.orNotFound.run(modern("ping"))
          _ <- finalized.get.timeout(5.seconds)
        yield res.status shouldBe Status.Ok
      }
    }
  }

  describe("GET and DELETE without sessions") {
    it("answers GET with 405 and Allow: POST") {
      withRoute() { f =>
        for res <- run(f, Request[IO](Method.GET, uri"/mcp").putHeaders(host))
        yield
          res.status shouldBe Status.MethodNotAllowed
          res.headers.get[headers.Allow].map(_.methods.toList) shouldBe Some(List(Method.POST))
      }
    }

    it("answers DELETE with 405, even with a session id") {
      withRoute() { f =>
        for
          plain <- run(f, Request[IO](Method.DELETE, uri"/mcp").putHeaders(host))
          withId <- run(
            f,
            Request[IO](Method.DELETE, uri"/mcp").putHeaders(host, Header.Raw(ci"Mcp-Session-Id", "a" * 32)),
          )
        yield
          plain.status shouldBe Status.MethodNotAllowed
          withId.status shouldBe Status.MethodNotAllowed
      }
    }

    it("answers GET with a session id with 405 if sessions are not supported") {
      withRoute() { f =>
        for res <- run(f, Request[IO](Method.GET, uri"/mcp").putHeaders(host, Header.Raw(ci"Mcp-Session-Id", "a" * 32)))
        yield res.status shouldBe Status.MethodNotAllowed
      }
    }

    it("answers initialize of a current client, which carries the protocol version, like any other method") {
      withRoute(stateless = respondWith(i => List(error(i, -32601)))) { f =>
        for
          res <- run(f, post(requestBody("initialize", id = 3), modernHeaders("initialize")*))
          body <- json(res)
          calls <- f.factory.statelessHandler.requests.get
        yield
          res.status shouldBe Status.NotFound
          errorCode(body) shouldBe Some(-32601)
          calls.map(_._1.method) shouldBe List("initialize")
      }
    }

    it("rejects initialize if sessions are not supported") {
      withRoute() { f =>
        for
          res <- run(f, post(requestBody("initialize", version = None, id = 3)))
          body <- json(res)
        yield
          res.status shouldBe Status.BadRequest
          res.headers.get(ci"Mcp-Session-Id") shouldBe None
          errorCode(body) shouldBe Some(-32600)
          body.hcursor.downField("id").as[Long] shouldBe Right(3L)
          body.hcursor.downField("error").downField("message").as[String].toOption.get should include("every request")
      }
    }

    it("ignores the session id of a request if sessions are not supported") {
      withRoute() { f =>
        for
          res <- run(f, modern("ping").putHeaders(Header.Raw(ci"Mcp-Session-Id", "a" * 32)))
          calls <- f.factory.statelessHandler.requests.get
        yield
          res.status shouldBe Status.Ok
          res.headers.get(ci"Mcp-Session-Id") shouldBe None
          calls should have size 1
      }
    }

    it("answers other methods with 405") {
      withRoute() { f =>
        for res <- run(f, Request[IO](Method.PUT, uri"/mcp").putHeaders(host))
        yield res.status shouldBe Status.MethodNotAllowed
      }
    }
  }

  describe("legacy sessions") {
    def withSessions[A](
      respond: JsonRpc.Request => Stream[IO, JsonRpc.Message] = okOnly,
      unsolicited: Stream[IO, JsonRpc.Message] = Stream.empty,
      config: McpServerRouteConfig = McpServerRouteConfig.default,
    )(test: (Fixture, FakeHandler) => IO[A]): A =
      withRoute(session = Some(respond), sessionUnsolicited = unsolicited, config = config)(f =>
        test(f, f.sessionHandler.get)
      )

    def initialize(f: Fixture, id: Long = 1): IO[(Response[IO], String)] =
      run(f, post(requestBody("initialize", version = None, id = id))).map(res =>
        res -> res.headers.get(ci"Mcp-Session-Id").map(_.head.value).getOrElse("")
      )

    def sessionPost(sid: String, body: Json) = post(body, "Mcp-Session-Id" -> sid)

    it("opens a session with initialize and answers with the session id") {
      withSessions() { (f, handler) =>
        for
          (res, sid) <- initialize(f)
          body <- json(res)
          infos <- f.factory.connections.get
          calls <- handler.requests.get
          stateless <- f.factory.statelessHandler.requests.get
          stored <- SessionId.parse(sid).traverse(f.store.get)
        yield
          res.status shouldBe Status.Ok
          sid should have length 32
          body.hcursor.downField("result").downField("ok").as[Boolean] shouldBe Right(true)
          infos should have size 1
          infos.head.additional.get("sessionId") shouldBe Some(sid.asJson)
          calls.map(_._1.method) shouldBe List("initialize")
          calls.head._2.connection.additional.get("sessionId") shouldBe Some(sid.asJson)
          stateless shouldBe empty
          stored.flatten shouldBe defined
      }
    }

    it("does not open a session for the initialize of a current client (a removed method)") {
      withSessions() { (f, _) =>
        for
          res <- run(f, post(requestBody("initialize"), modernHeaders("initialize")*))
          infos <- f.factory.connections.get
          stateless <- f.factory.statelessHandler.requests.get
        yield
          res.headers.get(ci"Mcp-Session-Id") shouldBe None
          infos shouldBe empty
          stateless.map(_._1.method) shouldBe List("initialize")
      }
    }

    it("routes the following requests with the header to the same handler, without header validation") {
      withSessions() { (f, handler) =>
        for
          (_, sid) <- initialize(f)
          res <- run(f, sessionPost(sid, requestBody("tools/list", version = None, id = 2)))
          calls <- handler.requests.get
          stateless <- f.factory.statelessHandler.requests.get
        yield
          res.status shouldBe Status.Ok
          calls.map(_._1.method) shouldBe List("initialize", "tools/list")
          stateless shouldBe empty
      }
    }

    it("answers with an event stream if the handler sends requests to the client") {
      val serverRequest = JsonRpc.Request(Id.IdInt(100), "sampling/createMessage", None)
      withSessions(respondWith(i => List(serverRequest, success(i)))) { (f, _) =>
        for
          (_, sid) <- initialize(f)
          res <- run(f, sessionPost(sid, requestBody("tools/call", id = 2)))
          body <- text(res)
        yield
          mediaType(res) shouldBe Some("text/event-stream")
          body should include("sampling/createMessage")
      }
    }

    it("answers an unknown session with 404") {
      withSessions() { (f, _) =>
        for
          unknown <- run(f, sessionPost("b" * 32, requestBody("tools/list", version = None)))
          malformed <- run(f, sessionPost("short", requestBody("tools/list", version = None)))
          notification <- run(
            f,
            sessionPost("b" * 32, Json.obj("jsonrpc" -> "2.0".asJson, "method" -> "notifications/initialized".asJson)),
          )
          get <- run(f, Request[IO](Method.GET, uri"/mcp").putHeaders(host, Header.Raw(ci"Mcp-Session-Id", "b" * 32)))
          delete <-
            run(f, Request[IO](Method.DELETE, uri"/mcp").putHeaders(host, Header.Raw(ci"Mcp-Session-Id", "b" * 32)))
        yield
          unknown.status shouldBe Status.NotFound
          malformed.status shouldBe Status.NotFound
          notification.status shouldBe Status.NotFound
          get.status shouldBe Status.NotFound
          delete.status shouldBe Status.NotFound
      }
    }

    it("routes notifications and responses of the client to the session handler") {
      withSessions() { (f, handler) =>
        for
          (_, sid) <- initialize(f)
          n <-
            run(f, sessionPost(sid, Json.obj("jsonrpc" -> "2.0".asJson, "method" -> "notifications/initialized".asJson)))
          r <- run(f, sessionPost(sid, Json.obj("jsonrpc" -> "2.0".asJson, "id" -> 100.asJson, "result" -> Json.obj())))
          notifications <- handler.notifications.get
          responses <- handler.responses.get
          nBody <- text(n)
        yield
          n.status shouldBe Status.Accepted
          nBody shouldBe ""
          r.status shouldBe Status.Accepted
          notifications.map(_._1.method) shouldBe List("notifications/initialized")
          responses.map(_._1.id) shouldBe List(Id.IdInt(100))
      }
    }

    it("relays the unsolicited messages on GET") {
      val message = JsonRpc.Notification("notifications/tools/list_changed", None)
      withSessions(unsolicited = Stream.emit(message) ++ Stream.never[IO]) { (f, _) =>
        for
          (_, sid) <- initialize(f)
          res <- run(f, Request[IO](Method.GET, uri"/mcp").putHeaders(host, Header.Raw(ci"Mcp-Session-Id", sid)))
          first <- res.bodyText.take(1).compile.string
        yield
          res.status shouldBe Status.Ok
          mediaType(res) shouldBe Some("text/event-stream")
          first should include("notifications/tools/list_changed")
      }
    }

    it("closes the session and releases the connection on DELETE") {
      withSessions() { (f, _) =>
        for
          (_, sid) <- initialize(f)
          before <- f.factory.released.get
          res <- run(f, Request[IO](Method.DELETE, uri"/mcp").putHeaders(host, Header.Raw(ci"Mcp-Session-Id", sid)))
          after <- f.factory.released.get
          again <- run(f, sessionPost(sid, requestBody("tools/list", version = None, id = 2)))
        yield
          before shouldBe 0
          res.status shouldBe Status.NoContent
          after shouldBe 1
          again.status shouldBe Status.NotFound
      }
    }

    it("still answers modern requests without a session statelessly") {
      withSessions() { (f, handler) =>
        for
          res <- run(f, modern("ping"))
          stateless <- f.factory.statelessHandler.requests.get
          calls <- handler.requests.get
        yield
          res.status shouldBe Status.Ok
          stateless should have size 1
          calls shouldBe empty
      }
    }

    it("keeps the GET stream alive with comments") {
      val config = McpServerRouteConfig(keepAliveInterval = 50.millis)
      withSessions(unsolicited = Stream.never[IO], config = config) { (f, _) =>
        for
          (_, sid) <- initialize(f)
          res <- run(f, Request[IO](Method.GET, uri"/mcp").putHeaders(host, Header.Raw(ci"Mcp-Session-Id", sid)))
          lines <- res.bodyText.through(fs2.text.lines).filter(_.nonEmpty).take(2).compile.toList
        yield lines.foreach(_ should startWith(":"))
      }
    }
  }
