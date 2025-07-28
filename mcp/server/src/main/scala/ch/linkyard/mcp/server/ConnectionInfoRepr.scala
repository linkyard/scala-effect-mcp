package ch.linkyard.mcp.server

import cats.MonadThrow
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection

private class ConnectionInfoRepr[F[_]: MonadThrow] private (
  authenticationRef: Ref[F, Authentication],
  override val connection: JsonRpcConnection.Info,
) extends McpServer.ConnectionInfo[F]:
  def authentication: F[Authentication] = authenticationRef.get

  private[server] def updateAuthentication(auth: Authentication): F[Unit] =
    authenticationRef.modify(old =>
      (old, auth) match
        case (Authentication.Anonymous, Authentication.Anonymous)           => (auth, true)
        case (Authentication.BearerToken(_), Authentication.BearerToken(_)) => (auth, true)
        case _                                                              => (old, false)
    ).ifM(
      ().pure[F],
      McpError.raise(ErrorCode.InvalidRequest, "Cannot change authentication mode after initialization").void,
    )

private object ConnectionInfoRepr:
  def apply[F[_]: Async](authentication: Authentication, connection: JsonRpcConnection.Info): F[ConnectionInfoRepr[F]] =
    Ref.of[F, Authentication](authentication).map(new ConnectionInfoRepr[F](_, connection))
