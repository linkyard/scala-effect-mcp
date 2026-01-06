package ch.linkyard.mcp.example.simpleAuthenticated

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.transport.http4s.McpServerRoute
import ch.linkyard.mcp.jsonrpc2.transport.http4s.MinimalOAuthAuthorizationServer
import ch.linkyard.mcp.jsonrpc2.transport.http4s.MinimalOAuthAuthorizationServer.ClientCredentials
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
      staticClient <- parseStaticClient(args.drop(1))
      _ <- IO.println(s"Using OIDC IdP $idp")
      _ <- staticClient match
        case Some(ClientCredentials(clientId = id)) => IO.println(s"Using static client with ID: $id")
        case None                                   => IO.println("No static client configured")
      _ <- program(idp, staticClient).useForever
    yield ExitCode.Success

  private def parseStaticClient(args: List[String]): IO[Option[ClientCredentials]] =
    (args.headOption, args.drop(1).headOption) match
      case (None, None)                         => None.pure[IO]
      case (Some(clientId), Some(clientSecret)) =>
        Some(ClientCredentials(clientId, clientSecret, _ => true)).pure[IO]
      case (Some(_), None) =>
        IO.raiseError(RuntimeException("Client ID provided but client secret is missing"))
      case (None, Some(_)) =>
        IO.raiseError(RuntimeException("Client secret provided but client ID is missing"))

  private def program(idp: Uri, staticClient: Option[ClientCredentials]): Resource[IO, Unit] =
    for
      given SessionStore[IO] <- SessionStore.inMemory[IO](30.minutes)
      handler = TheServer().jsonRpcConnectionHandler(logError)
      given Client[IO] <- EmberClientBuilder.default[IO].build
      root = Uri.Path.Root / "_api"
      authServer <- MinimalOAuthAuthorizationServer.fromOidcConfig(idp, staticClient)
      middleware =
        OAuthMiddleware(
          name = "simple-authenticated-server",
          authorizationServers = authServer.rootUri :: Nil,
          scopes = List("openid"),
          validateToken = t => t.nonEmpty.pure, // check the token here, using jwt signature check or whatelse
          root = root,
        )
      mcpRoute = McpServerRoute.route(handler, root)
      route = middleware.wellKnownRoutes <+> authServer.route <+> middleware.protectMcp(mcpRoute)
      _ <- EmberServerBuilder.default[IO]
        .withHost(Host.fromString("127.0.0.1").get)
        .withPort(Port.fromInt(18283).get)
        .withHttpApp(route.orNotFound)
        .build
    yield ()

  private def logError(error: Exception): IO[Unit] =
    Logger[IO].warn(error)(s"Error parsing request data")
