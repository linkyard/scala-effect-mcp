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

class OAuthMiddleware(
  name: String,
  authorizationServers: List[Uri],
  scopes: List[String],
  validateToken: String => IO[Boolean],
  audienceOverride: Option[Uri] = None,
):
  def protectMcp(mcpRoute: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli { req =>
    if req.uri.path.startsWithString("/mcp") then protect(mcpRoute).run(req)
    else mcpRoute.run(req)
  }

  def protect: HttpRoutes[IO] => HttpRoutes[IO] = { routes =>
    Kleisli { (req: Request[IO]) =>
      OptionT {
        req.headers.get[headers.Authorization] match {
          case Some(headers.Authorization(Credentials.Token(AuthScheme.Bearer, token))) =>
            validateToken(token).flatMap {
              case true  => routes(req).value
              case false => unauthorizedResponse(req).map(Some(_))
            }
          case _ => unauthorizedResponse(req).map(Some(_))
        }
      }
    }
  }

  def wellKnownRoutes: HttpRoutes[IO] =
    def protectedResource(req: Request[IO]) = Ok(Json.obj(
      "resource" -> audience(req).asJson,
      "authorization_servers" -> authorizationServers.map(makeAbsolute(_, req)).asJson,
      "resource_name" -> name.asJson,
      "supported_scopes" -> Json.arr(scopes.map(_.asJson)*),
      "bearer_methods_supported" -> Json.arr("header".asJson),
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

  private def unauthorizedResponse(req: Request[IO]): IO[Response[IO]] =
    val root = req.serverRoot
    val resourceMetadataUrl = s"$root.well-known/oauth-protected-resource"
    val challenge = headers.`WWW-Authenticate`(Challenge(
      scheme = "Bearer",
      realm = audience(req).toString,
      params = Map("resource_server" -> resourceMetadataUrl),
    ))
    Unauthorized(challenge)
  end unauthorizedResponse
