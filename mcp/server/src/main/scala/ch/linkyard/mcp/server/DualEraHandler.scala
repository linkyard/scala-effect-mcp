package ch.linkyard.mcp.server

import cats.effect.kernel.Async
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.protocol.legacy.LegacyCodec
import fs2.Stream

/** The handler of a connection (stdio) that serves clients of both generations: a client that starts with `initialize`
  * is a legacy client for the whole connection, all other requests are served statelessly.
  */
private[server] final class DualEraHandler[F[_]](modern: ModernHandler[F], legacy: LegacyConnection[F])(using
  F: Async[F]
) extends JsonRpcHandler[F]:
  override def request(request: JsonRpc.Request, context: JsonRpcHandler.Context): Stream[F, JsonRpc.Message] =
    Stream.eval(legacy.isEstablished).flatMap { established =>
      // ping is allowed before the handshake in the earlier versions
      if established || request.method == LegacyCodec.Method.Initialize || request.method == LegacyCodec.Method.Ping then
        legacy.request(request, context)
      else modern.handle(request, context)
    }

  override def notification(notification: JsonRpc.Notification, context: JsonRpcHandler.Context): F[Unit] =
    legacy.isEstablished.ifM(legacy.notification(notification, context), F.unit)

  override def response(response: JsonRpc.Response, context: JsonRpcHandler.Context): F[Unit] =
    legacy.response(response, context)

  override def unsolicited: Stream[F, JsonRpc.Message] = legacy.unsolicited

  /** The cancellation is the same in both generations, the connection stops the request. */
  override def cancelledRequest(notification: JsonRpc.Notification): Option[JsonRpc.Id] =
    modernCancelled(notification)

  private def modernCancelled(notification: JsonRpc.Notification): Option[JsonRpc.Id] =
    import ch.linkyard.mcp.protocol.McpCodec
    import ch.linkyard.mcp.protocol.McpCodec.toJsonRpc
    McpCodec.decodeNotification(notification).toOption.map(_.requestId.toJsonRpc)
