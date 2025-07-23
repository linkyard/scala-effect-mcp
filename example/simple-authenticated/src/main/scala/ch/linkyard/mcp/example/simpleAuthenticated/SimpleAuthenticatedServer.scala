package ch.linkyard.mcp.example.simpleAuthenticated

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.transport.http4s.McpServerRoute
import ch.linkyard.mcp.jsonrpc2.transport.http4s.OAuthAuthorizationServer
import ch.linkyard.mcp.jsonrpc2.transport.http4s.OAuthMiddleware
import ch.linkyard.mcp.jsonrpc2.transport.http4s.SessionStore
import ch.linkyard.mcp.server.*
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.DurationInt

object SimpleAuthenticatedServer extends IOApp:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  override def run(args: List[String]): IO[ExitCode] =
    for
      idpString <-
        args.headOption.toRight(RuntimeException("Missing IDP (e.g. https://id.acme.local/realm/example)")).liftTo[IO]
      idp <- Uri.fromString(idpString).liftTo[IO]
      _ <- IO.println(s"Using OIDC IdP $idp")
      _ <- program(idp).useForever
    yield ExitCode.Success

  private def program(idp: Uri): Resource[IO, Unit] =
    for
      given SessionStore[IO] <- SessionStore.inMemory[IO](30.minutes)
      handler = TheServer().jsonRpcConnectionHandler(logError)
      given Client[IO] <- EmberClientBuilder.default[IO].build
      authServer <- OAuthAuthorizationServer.fromOidcServer(idp)
      middleware =
        OAuthMiddleware(
          name = "simple-authenticated-server",
          authorizationServers = authServer.rootUri :: Nil,
          scopes = List("openid"),
          t => t.nonEmpty.pure, // check the token here, using jwt signature check or whatelse
        )
      mcpRoute = McpServerRoute.route(handler)
      route = middleware.wellKnownRoutes <+> authServer.route <+> middleware.protectMcp(mcpRoute)
      _ <- EmberServerBuilder.default[IO]
        .withHost(Host.fromString("127.0.0.1").get)
        .withPort(Port.fromInt(18283).get)
        .withHttpApp(route.orNotFound)
        .build
    yield ()

  private def logError(error: Exception): IO[Unit] =
    Logger[IO].warn(error)(s"Error parsing request data")
