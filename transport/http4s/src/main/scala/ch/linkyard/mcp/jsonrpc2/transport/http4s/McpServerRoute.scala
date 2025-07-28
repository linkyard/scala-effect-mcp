package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpc.Notification
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnectionHandler
import com.comcast.ip4s.Host
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import org.http4s.dsl.io.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object McpServerRoute:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  def route[Auth](handler: JsonRpcConnectionHandler[IO], root: Path = Root)(using
    store: SessionStore[IO]
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ POST -> `root` / "mcp" => req.attemptAs[JsonRpc.Message].value.flatMap {
          case Right(init @ JsonRpc.Request(_, "initialize", _)) =>
            Logger[IO].trace(s"Opening new session for ${init.asJson.noSpaces}") >>
              openSession(init, handler, req)
          case Right(message) =>
            Logger[IO].trace(s"Received request ${message.asJson.noSpaces}") >>
              withSession(req)(conn => handleMessage(message, conn, req.authentication))
          case Left(decodeFailure) =>
            Logger[IO].info(s"Failed to decode a message: ${decodeFailure.getMessage}") >>
              BadRequest(s"Invalid json rpc message: ${decodeFailure.getMessage}")
        }
      case req @ GET -> `root` / "mcp" => // stream not directly request related messages
        Logger[IO].debug("Opening message stream") >>
          withSession(req)(conn =>
            val stream = conn.streamNonRequestRelated.map(_.toSse)
            Ok(stream, Header.Raw(ci"Content-Type", "text/event-stream")).withSessionId(conn.sessionId)
          )
      case req @ DELETE -> `root` / "mcp" => req.sessionId match
          case Some(sessionId) =>
            Logger[IO].info(s"Terminating session ${sessionId}") >>
              store.close(sessionId) >> NoContent()
          case None => BadRequest("no session id provided")
    }

  private def openSession(init: JsonRpc.Request, handler: JsonRpcConnectionHandler[IO], req: Request[IO])(using
    store: SessionStore[IO]
  ): IO[Response[IO]] =
    for
      info: JsonRpcConnection.Info.Http = JsonRpcConnection.Info.Http(
        server =
          for
            h <- req.serverHost
            host <- Host.fromString(h.value)
            port = req.serverHostPort.getOrElse(req.scheme.defaultPort)
          yield (host -> port),
        client = req.clientIp,
        additional = Map.empty,
      )
      conn <- StatefulConnection.create[IO](info)
      (_, cleanup) <- handler.open(conn.connection).allocated
      _ <- store.open(conn, cleanup)
      _ <- Logger[IO].info(s"Opening new mcp session ${conn.sessionId}")
      _ <- conn.receivedFromClient(init.withAuth(req.authentication))
      response <- streamUntilResponse(init.id, conn)
    yield response
  end openSession

  private def handleMessage(
    message: JsonRpc.Message,
    conn: StatefulConnection[IO],
    auth: Authentication,
  ): IO[Response[IO]] = message match
    case request: JsonRpc.Request =>
      conn.receivedFromClient(request.withAuth(auth)) >> streamUntilResponse(request.id, conn)
    case response: JsonRpc.Response => // no response or follow ups expected
      conn.receivedFromClient(response.withAuth(auth)) >> NoContent()
    case notification: Notification => // no response or follow ups expected
      conn.receivedFromClient(notification.withAuth(auth)) >> NoContent()
  end handleMessage

  private def streamUntilResponse(
    id: JsonRpc.Id,
    conn: StatefulConnection[IO],
  ): IO[Response[IO]] =
    val stream = conn.streamRequestReleated(id).map(_.toSse)
    Ok(stream).withSessionId(conn.sessionId)

  private def withSession(req: Request[IO])(f: StatefulConnection[IO] => IO[Response[IO]])(using
    store: SessionStore[IO]
  ): IO[Response[IO]] =
    req.sessionId match
      case Some(sessionId) => store.get(sessionId).flatMap {
          case Some(session) => f(session)
          case None          => NotFound("Session not found")
        }
      case None => NotFound("Session not found")
  end withSession
end McpServerRoute

extension (req: Request[IO])
  private def sessionId: Option[SessionId] =
    req.headers.get(ci"Mcp-Session-Id").map(_.head.value).flatMap(SessionId.parse)

extension (r: IO[Response[IO]])
  private def withSessionId(id: SessionId): IO[Response[IO]] =
    r.map(_.putHeaders(Header.Raw(ci"Mcp-Session-Id", id.asString)))
