package ch.linkyard.mcp.server

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandlerFactory
import ch.linkyard.mcp.protocol.McpCodec
import ch.linkyard.mcp.protocol.McpCodec.toJsonRpc

/** The handlers of a server for the transports. */
private[server] class McpServerHandlers[F[_]](core: ServerCore[F], config: McpServerConfig, logError: Throwable => F[Unit])(
  using F: Async[F]
) extends JsonRpcHandlerFactory[F]:
  private val modern = ModernHandler(core, List(ModernHandler.Version))

  override val stateless: JsonRpcHandler[F] = new JsonRpcHandler[F]:
    override def request(request: JsonRpc.Request, context: JsonRpcHandler.Context): fs2.Stream[F, JsonRpc.Message] =
      modern.handle(request, context)
    override def notification(notification: JsonRpc.Notification, context: JsonRpcHandler.Context): F[Unit] = F.unit
    override def response(response: JsonRpc.Response, context: JsonRpcHandler.Context): F[Unit] = F.unit
    override def unsolicited: fs2.Stream[F, JsonRpc.Message] = fs2.Stream.empty
    override def cancelledRequest(notification: JsonRpc.Notification): Option[JsonRpc.Id] =
      McpCodec.decodeNotification(notification).toOption.map(_.requestId.toJsonRpc)

  override def connection(info: JsonRpcConnection.Info): Resource[F, JsonRpcHandler[F]] = Resource.pure(stateless)

  override def supportsSessions: Boolean = false
