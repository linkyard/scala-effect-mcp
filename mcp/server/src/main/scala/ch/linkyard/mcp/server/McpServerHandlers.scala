package ch.linkyard.mcp.server

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandlerFactory
import ch.linkyard.mcp.protocol.McpCodec
import ch.linkyard.mcp.protocol.McpCodec.toJsonRpc
import ch.linkyard.mcp.protocol.legacy.LegacyVersion

/** The handlers of a server for the transports. */
private[server] class McpServerHandlers[F[_]](core: ServerCore[F])(using F: Async[F]) extends JsonRpcHandlerFactory[F]:
  private val supportedVersions =
    if core.config.supportLegacyClients then ModernHandler.Version :: LegacyVersion.all else List(ModernHandler.Version)
  private val modern = ModernHandler(core, supportedVersions)

  override val stateless: JsonRpcHandler[F] = new JsonRpcHandler[F]:
    override def request(request: JsonRpc.Request, context: JsonRpcHandler.Context): fs2.Stream[F, JsonRpc.Message] =
      modern.handle(request, context)
    override def notification(notification: JsonRpc.Notification, context: JsonRpcHandler.Context): F[Unit] = F.unit
    override def response(response: JsonRpc.Response, context: JsonRpcHandler.Context): F[Unit] = F.unit
    override def unsolicited: fs2.Stream[F, JsonRpc.Message] = fs2.Stream.empty
    override def cancelledRequest(notification: JsonRpc.Notification): Option[JsonRpc.Id] =
      McpCodec.decodeNotification(notification).toOption.map(_.requestId.toJsonRpc)

  override def connection(info: JsonRpcConnection.Info): Resource[F, JsonRpcHandler[F]] =
    if !core.config.supportLegacyClients then Resource.pure(stateless)
    else
      LegacyConnection.resource(core).map { legacy =>
        info match
          case _: JsonRpcConnection.Info.Http => legacy // a session was opened with initialize
          case _                              => DualEraHandler(modern, legacy)
      }

  override def supportsSessions: Boolean = core.config.supportLegacyClients
