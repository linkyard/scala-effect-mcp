package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.server.middleware.CORS

import scala.collection.immutable.ListMap

/** Result of validating an access token. */
enum TokenValidation:
  /** The token is valid and grants access. */
  case Valid

  /** The token is expired, malformed or otherwise not acceptable. Answered with 401. */
  case Invalid

  /** The token is valid but lacks scopes. Answered with 403 and `error="insufficient_scope"`, so that the client can
    * start a step-up authorization with the given scopes. Include all scopes needed for the operation.
    */
  case InsufficientScope(requiredScopes: List[String])

object TokenValidation:
  /** For validators that only decide between valid and invalid. */
  def of(valid: Boolean): TokenValidation = if valid then Valid else Invalid

/** Protects routes with OAuth bearer tokens and serves the OAuth 2.0 Protected Resource Metadata (RFC 9728).
  *
  * @param authorizationServers
  *   issuer URLs of the authorization servers. Pointing directly to the upstream authorization server is recommended.
  * @param scopes
  *   scopes advertised as `scopes_supported` in the protected resource metadata and in the `scope` parameter of the 401
  *   challenge
  */
class OAuthMiddleware(
  name: String,
  authorizationServers: List[Uri],
  scopes: List[String],
  validateToken: String => IO[TokenValidation],
  root: Path = Root,
  audienceOverride: Option[Uri] = None,
):
  import OAuthMiddleware.*

  def protectMcp(mcpRoute: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli { req =>
    if req.uri.path.startsWith(root / "mcp") then protect(mcpRoute).run(req)
    else mcpRoute.run(req)
  }

  def protect: HttpRoutes[IO] => HttpRoutes[IO] = { routes =>
    Kleisli { (req: Request[IO]) =>
      OptionT {
        req.headers.get[headers.Authorization] match {
          case Some(headers.Authorization(Credentials.Token(AuthScheme.Bearer, token))) =>
            validateToken(token).flatMap {
              case TokenValidation.Valid =>
                routes(req.withAttribute(AuthenticationTokenAttribute, token)).value
              case TokenValidation.Invalid =>
                unauthorizedResponse(req, invalidToken = true).map(Some(_))
              case TokenValidation.InsufficientScope(required) =>
                insufficientScopeResponse(req, required).map(Some(_))
            }
          case _ => unauthorizedResponse(req, invalidToken = false).map(Some(_))
        }
      }
    }
  }

  def wellKnownRoutes: HttpRoutes[IO] =
    def protectedResource(req: Request[IO]) =
      Ok(protectedResourceMetadata(
        resource = audience(req),
        authorizationServers = authorizationServers.map(makeAbsolute(_, req)),
        name = name,
        scopes = scopes,
      ))
    CORS.policy.withAllowOriginAll(HttpRoutes.of[IO] {
      case req @ GET -> Root / ".well-known" / "oauth-protected-resource" =>
        protectedResource(req)
      case req @ GET -> Root / ".well-known" / "oauth-protected-resource" / "mcp" =>
        protectedResource(req)
    })

  private def makeAbsolute(uri: Uri, req: Request[IO]): Uri = uri.scheme match
    case None =>
      val root = req.serverRoot
      uri.copy(scheme = root.scheme, authority = root.authority)
    case Some(value) => uri

  private def audience(req: Request[IO]) = audienceOverride.getOrElse(req.serverRoot)

  // Must be a path served by wellKnownRoutes
  private def resourceMetadataUrl(req: Request[IO]): String =
    (req.serverRoot / ".well-known" / "oauth-protected-resource").toString

  private def challenge(req: Request[IO], params: List[(String, String)]): headers.`WWW-Authenticate` =
    headers.`WWW-Authenticate`(Challenge(
      scheme = "Bearer",
      realm = audience(req).toString,
      params = ListMap.from(params :+ ("resource_metadata" -> resourceMetadataUrl(req))),
    ))

  private def scopeParam(scopes: List[String]): List[(String, String)] =
    if scopes.isEmpty then Nil else List("scope" -> scopes.mkString(" "))

  private def unauthorizedResponse(req: Request[IO], invalidToken: Boolean): IO[Response[IO]] =
    val error = if invalidToken then List("error" -> "invalid_token") else Nil
    Unauthorized(challenge(req, error ++ scopeParam(scopes)))

  private def insufficientScopeResponse(req: Request[IO], required: List[String]): IO[Response[IO]] =
    Forbidden().map(_.putHeaders(challenge(req, ("error" -> "insufficient_scope") :: scopeParam(required))))
end OAuthMiddleware

object OAuthMiddleware:
  def apply(
    name: String,
    authorizationServers: List[Uri],
    scopes: List[String],
    validateToken: String => IO[TokenValidation],
    root: Path = Root,
    audienceOverride: Option[Uri] = None,
  ): OAuthMiddleware =
    new OAuthMiddleware(name, authorizationServers, scopes, validateToken, root, audienceOverride)

  /** RFC 9728 protected resource metadata document. */
  private[http4s] def protectedResourceMetadata(
    resource: Uri,
    authorizationServers: List[Uri],
    name: String,
    scopes: List[String],
  ): Json =
    Json.obj(
      "resource" -> resource.toString.asJson,
      "authorization_servers" -> authorizationServers.map(_.toString).asJson,
      "resource_name" -> name.asJson,
      "scopes_supported" -> scopes.asJson,
      "bearer_methods_supported" -> Json.arr("header".asJson),
    )
