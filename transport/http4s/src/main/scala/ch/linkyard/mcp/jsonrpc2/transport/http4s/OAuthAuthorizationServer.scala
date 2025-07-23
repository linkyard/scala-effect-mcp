package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.data.OptionT
import cats.effect.IO
import cats.effect.kernel.Resource
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.server.middleware.CORS

/** For oidc server that do not support the .well-known/oauth-authorization-server */
class OAuthAuthorizationServer(authConfig: IO[Json]):
  def route = wellKnownRoutes

  def rootUri: Uri = Uri(None, None, Root, Query.empty, None)

  private def wellKnownRoutes: HttpRoutes[IO] = CORS.policy.withAllowOriginAll(HttpRoutes.of {
    case GET -> Root / ".well-known" / "oauth-authorization-server" =>
      Ok(authConfig)
  })

object OAuthAuthorizationServer:
  def fromOidcServer(root: Uri)(using client: Client[IO]): Resource[IO, OAuthAuthorizationServer] =
    def fetchConfig =
      val req = Request[IO](
        method = GET,
        uri = root / ".well-known" / "openid-configuration",
      )
      client.expect[Json](req)
    Resource.pure(OAuthAuthorizationServer(fetchConfig))
