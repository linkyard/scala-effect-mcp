package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import cats.effect.kernel.Resource
import ch.linkyard.mcp.jsonrpc2.transport.http4s.MinimalOAuthAuthorizationServer.ClientCredentials
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.server.middleware.CORS
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Minimal OAuth Authorization Server that takes auth and token URLs and issuer from external */
class MinimalOAuthAuthorizationServer(
  issuer: String,
  authorizationEndpoint: Uri,
  tokenEndpoint: Uri,
  pseudoDynamicClient: Option[ClientCredentials] = None,
):
  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  def route = wellKnownRoutes
  def rootUri: Uri = Uri(None, None, Root, Query.empty, None)

  // Hardcoded supported response types
  private val responseTypesSupported =
    List("code", "token", "id_token", "code id_token", "code token", "id_token token", "code id_token token")

  private def parseRegistrationRequest(req: Request[IO], credentials: ClientCredentials): IO[Either[String, String]] =
    req.as[Json].map { json =>
      val hc = json.hcursor
      val requestedRedirectUris = hc.get[List[String]]("redirect_uris").toOption.getOrElse(Nil)

      requestedRedirectUris.headOption match
        case Some(redirectUri) =>
          // Validate redirect URI against filter
          if credentials.redirectUriFilter(redirectUri) then
            Right(redirectUri)
          else
            Left("redirect_uri not allowed")
        case None =>
          Left("redirect_uris is required and must contain at least one URI")
    }

  private def registrationResponse(credentials: ClientCredentials, redirectUri: String): Json =
    Json.obj(
      "client_id" -> credentials.clientId.asJson,
      "client_secret" -> credentials.clientSecret.asJson,
      "client_id_issued_at" -> (System.currentTimeMillis() / 1000).asJson,
      "client_secret_expires_at" -> 0.asJson, // 0 means never expires
      "redirect_uris" -> List(redirectUri).asJson,
    )

  private def wellKnownRoutes: HttpRoutes[IO] = CORS.policy.withAllowOriginAll(HttpRoutes.of {
    case req @ GET -> Root / ".well-known" / "oauth-authorization-server" =>
      val config = Json.obj(
        "issuer" -> issuer.asJson,
        "authorization_endpoint" -> authorizationEndpoint.toString.asJson,
        "token_endpoint" -> tokenEndpoint.toString.asJson,
        "response_types_supported" -> responseTypesSupported.asJson,
      )

      // Add registration_endpoint if pseudoDynamicClient is provided
      val finalConfig = pseudoDynamicClient match
        case Some(_) =>
          val registrationEndpoint =
            (req.serverRoot / ".well-known" / "oauth-authorization-server" / "register").toString
          config.deepMerge(Json.obj("registration_endpoint" -> registrationEndpoint.asJson))
        case None => config

      Ok(finalConfig)
    case req @ POST -> Root / ".well-known" / "oauth-authorization-server" / "register" =>
      pseudoDynamicClient match
        case Some(credentials) =>
          parseRegistrationRequest(req, credentials).flatMap {
            case Right(redirectUri) =>
              Logger[IO].info(s"New client registered: ${credentials.clientId} with redirect_uri: $redirectUri") >>
                Ok(registrationResponse(credentials, redirectUri))
            case Left(error) => BadRequest(error)
          }
        case None => NotFound()
  })

object MinimalOAuthAuthorizationServer:
  def fromOidcConfig(
    root: Uri,
    /** */
    pseudoDynamicClient: Option[ClientCredentials] = None,
  )(using client: Client[IO]): Resource[IO, MinimalOAuthAuthorizationServer] =
    def fetchConfig =
      val req = Request[IO](
        method = GET,
        uri = root / ".well-known" / "openid-configuration",
      )
      client.expect[Json](req)

    Resource.eval(fetchConfig.flatMap { json =>
      val hc = json.hcursor
      for
        issuer <- IO.fromOption(hc.get[String]("issuer").toOption)(
          IllegalArgumentException("issuer not found in OIDC configuration")
        )
        authEndpointStr <- IO.fromOption(hc.get[String]("authorization_endpoint").toOption)(
          IllegalArgumentException("authorization_endpoint not found in OIDC configuration")
        )
        tokenEndpointStr <- IO.fromOption(hc.get[String]("token_endpoint").toOption)(
          IllegalArgumentException("token_endpoint not found in OIDC configuration")
        )
        authEndpoint <- IO.fromEither(Uri.fromString(authEndpointStr))
        tokenEndpoint <- IO.fromEither(Uri.fromString(tokenEndpointStr))
      yield MinimalOAuthAuthorizationServer(issuer, authEndpoint, tokenEndpoint, pseudoDynamicClient)
    })

  case class ClientCredentials(
    clientId: String,
    clientSecret: String,
    redirectUriFilter: String => Boolean
  )
