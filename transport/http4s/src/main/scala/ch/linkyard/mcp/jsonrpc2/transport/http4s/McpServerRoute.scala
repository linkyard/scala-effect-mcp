package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import cats.effect.Resource
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandlerFactory
import com.comcast.ip4s.Host
import fs2.Pull
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.Origin
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try

/** The MCP Streamable HTTP transport (protocol revision 2026-07-28) with a session path for older clients.
  *
  * Requests of current clients are self contained and are handled by the stateless handler of the factory. The
  * transport validates the standard request headers (`MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name`) against the body
  * and answers a mismatch with 400 and the JSON-RPC error -32020. The custom `Mcp-Param-*` headers are not validated
  * because the transport does not know the schemas of the tools, this is up to the handler.
  *
  * Clients of older protocol revisions start with an `initialize` request, which opens a session (only if the factory
  * supports sessions). All further messages of such a client carry the `Mcp-Session-Id` header and are routed to the
  * handler of the session. Without session support `initialize` is rejected and the session header is ignored.
  *
  * A request is answered with a single `application/json` response, or with a `text/event-stream` if the handler emits
  * other messages before the response (`subscriptions/listen` is always answered with a stream). Closing the connection
  * cancels the stream of the handler. Resumability (event ids, `Last-Event-ID`) is not supported.
  */
object McpServerRoute:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val ProtocolVersionMeta = "io.modelcontextprotocol/protocolVersion"
  private val NamedMethods = Map("tools/call" -> "name", "prompts/get" -> "name", "resources/read" -> "uri")

  def route(
    factory: JsonRpcHandlerFactory[IO],
    config: McpServerRouteConfig = McpServerRouteConfig.default,
    root: Path = Root,
  )(using store: SessionStore[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ POST -> `root` / "mcp" =>
        withOrigin(req, config)(post(req, factory, config))
      case req @ GET -> `root` / "mcp" =>
        withOrigin(req, config)(get(req, factory, config))
      case req @ DELETE -> `root` / "mcp" =>
        withOrigin(req, config)(delete(req, factory))
      case _ -> `root` / "mcp" =>
        MethodNotAllowed(headers.Allow(Method.POST, Method.GET, Method.DELETE))
    }

  // POST

  private def post(req: Request[IO], factory: JsonRpcHandlerFactory[IO], config: McpServerRouteConfig)(using
    store: SessionStore[IO]
  ): IO[Response[IO]] =
    parseMessage(req).flatMap {
      case Left(error) =>
        Logger[IO].info(s"Failed to decode a message: ${error.message}") >>
          jsonRpcError(Status.BadRequest, error.id, JsonRpc.ErrorCode.ParseError, error.message)
      case Right(init @ JsonRpc.Request(id, "initialize", _)) =>
        if factory.supportsSessions then
          Logger[IO].trace(s"Opening new session for $init") >> openSession(init, req, factory, config)
        else
          jsonRpcError(
            Status.BadRequest,
            Some(id),
            JsonRpc.ErrorCode.InvalidRequest,
            "This server only supports protocol versions that send the protocol version with every request",
          )
      case Right(request: JsonRpc.Request) =>
        Logger[IO].trace(s"Received request $request") >>
          (sessionId(req, factory) match
            case Some(sid) =>
              withSession(sid, Some(request.id)) { session =>
                answer(request, session.handler.request(request, context(req, session.info)), config, Nil)
              }
            case None => stateless(request, req, factory, config))
      case Right(notification: JsonRpc.Notification) =>
        Logger[IO].trace(s"Received notification $notification") >>
          (sessionId(req, factory) match
            case Some(sid) =>
              withSession(sid, None)(session =>
                session.handler.notification(notification, context(req, session.info)) >> Accepted()
              )
            case None => Accepted())
      case Right(response: JsonRpc.Response) =>
        Logger[IO].trace(s"Received response $response") >>
          (sessionId(req, factory) match
            case Some(sid) =>
              withSession(sid, Some(response.id))(session =>
                session.handler.response(response, context(req, session.info)) >> Accepted()
              )
            case None =>
              jsonRpcError(
                Status.BadRequest,
                Some(response.id),
                JsonRpc.ErrorCode.InvalidRequest,
                "Responses are only supported in a session",
              ))
    }

  private def stateless(
    request: JsonRpc.Request,
    req: Request[IO],
    factory: JsonRpcHandlerFactory[IO],
    config: McpServerRouteConfig,
  ): IO[Response[IO]] =
    validateHeaders(req, request) match
      case Left(message) =>
        Logger[IO].debug(s"Rejected request ${request.id}: $message") >>
          jsonRpcError(Status.BadRequest, Some(request.id), JsonRpc.ErrorCode.Other(HeaderMismatch), message)
      case Right(()) =>
        answer(request, factory.stateless.request(request, context(req, httpInfo(req, Map.empty))), config, Nil)

  private def openSession(
    init: JsonRpc.Request,
    req: Request[IO],
    factory: JsonRpcHandlerFactory[IO],
    config: McpServerRouteConfig,
  )(using store: SessionStore[IO]): IO[Response[IO]] =
    for
      sessionId <- SessionId.generate[IO]
      info = httpInfo(req, Map("sessionId" -> sessionId.asString.asJson))
      allocated <- factory.connection(info).allocated
      (handler, release) = allocated
      session = Session(sessionId, handler, info, release)
      _ <- store.open(session).onError(_ => release)
      _ <- Logger[IO].info(s"Opening new mcp session $sessionId for ${describe(info)}")
      response <- answer(
        init,
        handler.request(init, context(req, info)),
        config,
        List(Header.Raw(ci"Mcp-Session-Id", sessionId.asString)),
      )
    yield response

  // GET and DELETE

  private def get(req: Request[IO], factory: JsonRpcHandlerFactory[IO], config: McpServerRouteConfig)(using
    store: SessionStore[IO]
  ): IO[Response[IO]] =
    sessionId(req, factory) match
      case Some(sid) =>
        withSession(sid, None) { session =>
          Logger[IO].debug(s"Opening message stream of session ${session.id}") >>
            sseResponse(withKeepAlive(session.handler.unsolicited.map(_.toSse), config), Nil).pure[IO]
        }
      case None => MethodNotAllowed(headers.Allow(Method.POST))

  private def delete(req: Request[IO], factory: JsonRpcHandlerFactory[IO])(using
    store: SessionStore[IO]
  ): IO[Response[IO]] =
    sessionId(req, factory) match
      case Some(sid) =>
        withSession(sid, None)(session =>
          Logger[IO].info(s"Terminating session ${session.id}") >> store.close(session.id) >> NoContent()
        )
      case None => MethodNotAllowed(headers.Allow(Method.POST))

  // answers

  private def answer(
    request: JsonRpc.Request,
    messages: Stream[IO, JsonRpc.Message],
    config: McpServerRouteConfig,
    extraHeaders: List[Header.ToRaw],
  ): IO[Response[IO]] =
    val safe = messages.handleErrorWith(e =>
      Stream.exec(Logger[IO].error(e)(s"Request ${request.id} failed")) ++
        Stream.emit(internalError(request.id))
    )
    val alwaysStream = request.method == "subscriptions/listen"
    // the lease keeps the stream (and its resources) open while the response is sent
    peek(safe).allocated.flatMap {
      case (Some((first: JsonRpc.Response, _)), release) if !alwaysStream =>
        release.as(jsonResponse(first).putHeaders(extraHeaders*))
      case (Some((first, rest)), release) =>
        val events = (Stream.emit(first) ++ rest).takeThrough {
          case _: JsonRpc.Response => false
          case _                   => true
        }.map(_.toSse).onFinalize(release)
        sseResponse(withKeepAlive(events, config), extraHeaders).pure[IO]
      case (None, release) =>
        release >> Logger[IO].error(s"Request ${request.id} was not answered") >>
          IO.pure(jsonResponse(internalError(request.id)).copy(status = Status.InternalServerError))
    }
  end answer

  /** Evaluates the stream up to the first message and returns the remaining stream, which must only be used until the
    * resource is released.
    */
  private def peek(
    messages: Stream[IO, JsonRpc.Message]
  ): Resource[IO, Option[(JsonRpc.Message, Stream[IO, JsonRpc.Message])]] =
    messages.pull.uncons1.flatMap(Pull.output1).stream.compile.resource.lastOrError

  private def internalError(id: JsonRpc.Id): JsonRpc.Message =
    JsonRpc.Response.Error(id, JsonRpc.ErrorCode.InternalError, "Internal error", None)

  private def jsonResponse(message: JsonRpc.Message): Response[IO] =
    Response[IO](statusOf(message)).withEntity(message.asJson)

  private def statusOf(message: JsonRpc.Message): Status = message match
    case JsonRpc.Response.Error(_, code, _, _) =>
      JsonRpc.ErrorCode.toInt(code) match
        case -32700 | -32600 | -32602 | HeaderMismatch | UnsupportedProtocolVersion | MissingRequiredClientCapability =>
          Status.BadRequest
        case -32601 => Status.NotFound
        case _      => Status.Ok
    case _ => Status.Ok

  private val HeaderMismatch = -32020
  private val UnsupportedProtocolVersion = -32021
  private val MissingRequiredClientCapability = -32022

  private def sseResponse(events: Stream[IO, ServerSentEvent], extraHeaders: List[Header.ToRaw]): Response[IO] =
    Response[IO](Status.Ok)
      .withEntity(events)
      .putHeaders(Header.Raw(ci"X-Accel-Buffering", "no"))
      .putHeaders(extraHeaders*)

  /** Merges comment events into the stream. They never delay the events and end with the stream. */
  private def withKeepAlive(events: Stream[IO, ServerSentEvent], config: McpServerRouteConfig) =
    events.mergeHaltL(Stream.fixedDelay[IO](config.keepAliveInterval).as(ServerSentEvent(comment = Some("keep-alive"))))

  // errors without a request, hand built because the id can be null

  private def errorJson(id: Option[JsonRpc.Id], code: JsonRpc.ErrorCode, message: String): Json =
    Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id" -> id.fold(Json.Null)(_.asJson),
      "error" -> Json.obj("code" -> JsonRpc.ErrorCode.toInt(code).asJson, "message" -> message.asJson),
    )

  private def jsonRpcError(
    status: Status,
    id: Option[JsonRpc.Id],
    code: JsonRpc.ErrorCode,
    message: String,
  ): IO[Response[IO]] =
    Response[IO](status).withEntity(errorJson(id, code, message)).pure[IO]

  private def sessionNotFound(id: Option[JsonRpc.Id]): IO[Response[IO]] =
    jsonRpcError(Status.NotFound, id, JsonRpc.ErrorCode.InvalidRequest, "Session not found")

  // request parsing

  private case class DecodeError(id: Option[JsonRpc.Id], message: String)

  private def parseMessage(req: Request[IO]): IO[Either[DecodeError, JsonRpc.Message]] =
    req.bodyText.compile.string.map { body =>
      io.circe.jawn.parse(body) match
        case Left(failure) => Left(DecodeError(None, s"Parse error: ${failure.message}"))
        case Right(json)   =>
          json.as[JsonRpc.Message].left.map(f =>
            DecodeError(json.hcursor.downField("id").as[JsonRpc.Id].toOption, s"Invalid JSON-RPC message: ${f.message}")
          )
    }

  /** The session id of the request, only if sessions are supported (the header is ignored otherwise). */
  private def sessionId(req: Request[IO], factory: JsonRpcHandlerFactory[IO]): Option[Option[SessionId]] =
    Option.when(factory.supportsSessions)(req.headers.get(ci"Mcp-Session-Id").map(_.head.value.trim))
      .flatMap(_.map(SessionId.parse))

  private def withSession(sid: Option[SessionId], id: Option[JsonRpc.Id])(f: Session[IO] => IO[Response[IO]])(using
    store: SessionStore[IO]
  ): IO[Response[IO]] =
    sid.traverse(store.get).map(_.flatten).flatMap {
      case Some(session) => f(session)
      case None          => sessionNotFound(id)
    }

  // header validation (Request Metadata and Server Validation)

  private def validateHeaders(req: Request[IO], request: JsonRpc.Request): Either[String, Unit] =
    def header(name: String) = req.headers.get(org.typelevel.ci.CIString(name)).map(_.head.value)
    def required(name: String) = header(name).toRight(s"Header mismatch: missing $name header")
    def param(name: String): Option[String] = request.params.flatMap(_(name)).flatMap(_.asString)
    val bodyVersion = request.params.flatMap(_("_meta")).flatMap(_.asObject)
      .flatMap(_(ProtocolVersionMeta)).flatMap(_.asString)
    for
      version <- required("MCP-Protocol-Version")
      _ <- Either.cond(
        bodyVersion.contains(version),
        (),
        s"Header mismatch: MCP-Protocol-Version header '$version' does not match the protocol version in the body" +
          bodyVersion.fold(" (missing)")(v => s" '$v'"),
      )
      method <- required("Mcp-Method")
      _ <- Either.cond(
        method == request.method,
        (),
        s"Header mismatch: Mcp-Method header '$method' does not match body value '${request.method}'",
      )
      _ <- NamedMethods.get(request.method).flatMap(param) match
        case None           => Right(()) // nothing to compare, the handler rejects the invalid params
        case Some(expected) =>
          for
            raw <- required("Mcp-Name")
            name <- decodeHeaderValue(raw)
            _ <- Either.cond(
              name == expected,
              (),
              s"Header mismatch: Mcp-Name header value '$name' does not match body value '$expected'",
            )
          yield ()
    yield ()
  end validateHeaders

  /** Decodes the Base64 sentinel format `=?base64?<value>?=`, other values are used as they are. */
  private def decodeHeaderValue(value: String): Either[String, String] =
    val prefix = "=?base64?"
    val suffix = "?="
    if value.length >= prefix.length + suffix.length && value.startsWith(prefix) && value.endsWith(suffix) then
      Try(new String(
        Base64.getDecoder.decode(value.substring(prefix.length, value.length - suffix.length)),
        StandardCharsets.UTF_8,
      )).toEither.left.map(_ => s"Header mismatch: invalid Base64 value in the Mcp-Name header")
    else Right(value)

  // origin validation

  private def withOrigin(req: Request[IO], config: McpServerRouteConfig)(
    f: => IO[Response[IO]]
  ): IO[Response[IO]] =
    if originAcceptable(req, config) then f
    else
      Logger[IO].info(s"Rejected request with origin ${req.headers.get(ci"Origin").map(_.head.value).orEmpty}") >>
        jsonRpcError(Status.Forbidden, None, JsonRpc.ErrorCode.InvalidRequest, "Origin not allowed")

  private def originAcceptable(req: Request[IO], config: McpServerRouteConfig): Boolean =
    req.headers.get[Origin] match
      case None         => req.headers.get(ci"Origin").isEmpty // an unparsable header is rejected
      case Some(origin) =>
        val sameHost = origin match
          case Origin.HostList(hosts) =>
            req.serverHost.exists(h => hosts.forall(_.host.renderString.equalsIgnoreCase(h.renderString)))
          case _ => false
        sameHost || config.originAllowed(origin)

  // context

  private def httpInfo(req: Request[IO], additional: Map[String, Json]): JsonRpcConnection.Info.Http =
    JsonRpcConnection.Info.Http(
      server =
        for
          h <- req.serverHost
          host <- Host.fromString(h.value)
          port = req.serverHostPort.getOrElse(req.scheme.defaultPort)
        yield (host -> port),
      client = req.clientIp,
      additional = additional,
    )

  private def context(req: Request[IO], info: JsonRpcConnection.Info): JsonRpcHandler.Context =
    JsonRpcHandler.Context(req.authentication, info)

  private def describe(info: JsonRpcConnection.Info.Http): String = (info.client, info.server) match
    case (Some(clientIp), Some(host, _)) => s"$clientIp to $host"
    case (Some(clientIp), None)          => s"$clientIp"
    case (None, Some(host, _))           => s"$host"
    case _                               => "local"
end McpServerRoute
