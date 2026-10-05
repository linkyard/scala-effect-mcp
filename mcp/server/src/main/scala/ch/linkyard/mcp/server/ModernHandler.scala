package ch.linkyard.mcp.server

import cats.effect.Deferred
import cats.effect.implicits.*
import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.McpCodec.DecodeError
import ch.linkyard.mcp.protocol.McpCodec.fromJsonRpc
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*

private[server] object ModernHandler:
  /** The version that is served without a handshake. */
  val Version = "2026-07-28"

/** Serves the requests of clients that send the protocol version and their capabilities with every request. */
private[server] final class ModernHandler[F[_]](core: ServerCore[F], supportedVersions: List[String])(using
  F: Async[F]
):
  def handle(rpc: JsonRpc.Request, context: JsonRpcHandler.Context): Stream[F, JsonRpc.Message] =
    def failure(code: ErrorCode, message: String, data: Option[Json] = None): Stream[F, JsonRpc.Message] =
      Stream.emit(JsonRpc.Response.Error(rpc.id, code, message, data))

    McpCodec.decodeRequest(rpc) match
      case Left(DecodeError.UnknownMethod(method)) => failure(ErrorCode.MethodNotFound, s"Method not found: $method")
      case Left(DecodeError.InvalidParams(error))  =>
        failure(ErrorCode.InvalidParams, s"Invalid params: ${error.message}")
      case Right(request) =>
        RequestInfo.fromMeta(request.meta) match
          case Left(message)                                                => failure(ErrorCode.InvalidParams, message)
          case Right(info) if info.protocolVersion != ModernHandler.Version =>
            failure(
              McpErrorCode.UnsupportedProtocolVersion,
              "Unsupported protocol version",
              Some(UnsupportedProtocolVersionData(supportedVersions, info.protocolVersion).asJson),
            )
          case Right(info) =>
            val client = ClientInfo(info.clientInfo, info.clientCapabilities)
            request match
              case listen: Subscriptions.Listen =>
                val env = RequestEnv[F](client, context.authentication, context.connection, _ => F.unit)
                subscription(listen, rpc.id, env)
              case other => single(other, rpc.id, client, context)
  end handle

  /** Runs the request in the background, its notifications (progress) are sent before the response. */
  private def single(
    request: ClientRequest,
    id: JsonRpc.Id,
    client: ClientInfo,
    context: JsonRpcHandler.Context,
  ): Stream[F, JsonRpc.Message] =
    Stream.eval(Queue.unbounded[F, Option[JsonRpc.Message]]).flatMap { queue =>
      val env = RequestEnv[F](
        client,
        context.authentication,
        context.connection,
        notification => queue.offer(Some(McpCodec.encodeNotification(notification))),
        context.headers.map(_.filter(_._1.startsWith("mcp-param-"))),
      )
      val execution = (request match
        case _: Discover => core.discover(supportedVersions).widen[ServerResponse]
        case other       => core.execute(other, env)
      ).map(response => McpCodec.encodeResponse(id.fromJsonRpc, response.withServerInfo(core.server.serverInfo)))
        .widen[JsonRpc.Message]
        .handleErrorWith(errorResponse(id, _))
      val run = execution.flatMap(message => queue.offer(Some(message))).guarantee(queue.offer(None))
      Stream.fromQueueNoneTerminated(queue).concurrently(Stream.eval(run))
    }

  /** Acknowledges the subscription and sends the notifications until the client cancels it. The sources of the
    * notifications are started before the acknowledgement is sent: a change that a client causes after it received the
    * acknowledgement must not get lost.
    */
  private def subscription(
    listen: Subscriptions.Listen,
    id: JsonRpc.Id,
    env: RequestEnv[F],
  ): Stream[F, JsonRpc.Message] =
    val meta = Meta(Meta.Key.SubscriptionId -> id.fromJsonRpc.asJson)
    val (accepted, notifications) = core.listen(listen.notifications, core.context(listen._meta, env))
    val acknowledged = McpCodec.encodeNotification(Subscriptions.Acknowledged(accepted, meta))
    val response = McpCodec.encodeResponse(id.fromJsonRpc, Subscriptions.Listen.Response(meta))
    Stream.eval((Deferred[F, Unit], Queue.bounded[F, Option[JsonRpc.Message]](1024)).tupled).flatMap {
      (started, queue) =>
        val pump = (Stream.exec(started.complete(()).void) ++
          notifications.map(n => McpCodec.encodeNotification(n.withMeta(meta))))
          .evalMap(message => queue.offer(Some(message))) ++ Stream.exec(queue.offer(None))
        (Stream.exec(started.get) ++ Stream.emit(acknowledged) ++ Stream.fromQueueNoneTerminated(queue) ++
          Stream.emit(response)).concurrently(pump)
    }

  private def errorResponse(id: JsonRpc.Id, error: Throwable): F[JsonRpc.Message] =
    Errors.protocolError(id, error, legacy = false) match
      case Some(response) => response.pure[F].widen
      case None           => core.logError(error).as(Errors.internal(id))
end ModernHandler

extension (response: ServerResponse)
  private[server] def withServerInfo(info: Implementation): ServerResponse =
    val serverInfo = Meta.Key.ServerInfo -> info.asJson
    response match
      case r: Discover.Response                        => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))
      case r: Tool.ListTools.Response                  => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))
      case r: Tool.CallTool.Response.Success           => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))
      case r: Tool.CallTool.Response.Error             => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))
      case r: Prompts.ListPrompts.Response             => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))
      case r: Prompts.GetPrompt.Response               => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))
      case r: Resources.ListResources.Response         => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))
      case r: Resources.ListResourceTemplates.Response => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))
      case r: Resources.ReadResource.Response          => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))
      case r: Subscriptions.Listen.Response            => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))
      case r: Completion.Complete.Response             => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))
      case r: InputRequiredResult                      => r.copy(_meta = r._meta.add(serverInfo._1, serverInfo._2))

extension (notification: ServerNotification)
  /** Adds the meta (the subscription id) to the notification. */
  private[server] def withMeta(meta: Meta): ServerNotification = notification match
    case n: Cancelled                  => n.copy(_meta = n._meta |+| meta)
    case n: ProgressNotification       => n.copy(_meta = n._meta |+| meta)
    case n: Prompts.ListChanged        => n.copy(_meta = n._meta |+| meta)
    case n: Resources.Updated          => n.copy(_meta = n._meta |+| meta)
    case n: Resources.ListChanged      => n.copy(_meta = n._meta |+| meta)
    case n: Tool.ListChanged           => n.copy(_meta = n._meta |+| meta)
    case n: Subscriptions.Acknowledged => n.copy(_meta = n._meta |+| meta)
