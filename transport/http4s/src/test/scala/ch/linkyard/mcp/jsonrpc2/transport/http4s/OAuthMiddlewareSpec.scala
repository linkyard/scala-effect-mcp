package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class OAuthMiddlewareSpec extends AnyFunSpec with Matchers:
  private val metadataUrl = "http://mcp.example.com/.well-known/oauth-protected-resource"
  private val host = headers.Host("mcp.example.com")

  private def middleware(validate: String => IO[TokenValidation]) =
    OAuthMiddleware(
      name = "test-server",
      authorizationServers = List(uri"https://auth.example.com"),
      scopes = List("files:read", "openid"),
      validateToken = validate,
    )

  private val echo: HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ POST -> Root / "mcp" =>
    Ok(req.authentication.toString)
  }

  private def mcpRequest(auth: Option[Authorization] = None): Request[IO] =
    Request[IO](
      Method.POST,
      uri"/mcp",
    ).putHeaders(host).putHeaders(auth.toList.map(Header.ToRaw.modelledHeadersToRaw(_))*)

  private def bearer(token: String) = Some(Authorization(Credentials.Token(AuthScheme.Bearer, token)))

  private def run(routes: HttpRoutes[IO], req: Request[IO]): Response[IO] =
    routes.orNotFound.run(req).unsafeRunSync()

  private def challenge(res: Response[IO]): Challenge =
    res.headers.get[headers.`WWW-Authenticate`].get.values.head

  private def validator(result: TokenValidation): String => IO[TokenValidation] = _ => IO.pure(result)

  describe("OAuthMiddleware.protect") {
    it("answers a missing header with a 401 challenge carrying resource_metadata and scope") {
      val res = run(middleware(validator(TokenValidation.Valid)).protectMcp(echo), mcpRequest())
      res.status shouldBe Status.Unauthorized
      val c = challenge(res)
      c.scheme shouldBe "Bearer"
      c.params.get("resource_metadata") shouldBe Some(metadataUrl)
      c.params.get("scope") shouldBe Some("files:read openid")
      c.params.get("error") shouldBe None
      c.params.keySet should not contain "resource_server"
    }

    it("answers a non-bearer scheme with a 401 challenge") {
      val req = mcpRequest(Some(Authorization(BasicCredentials("user", "pass"))))
      val res = run(middleware(validator(TokenValidation.Valid)).protectMcp(echo), req)
      res.status shouldBe Status.Unauthorized
      challenge(res).params.get("resource_metadata") shouldBe Some(metadataUrl)
    }

    it("answers an invalid token with 401 and error invalid_token") {
      val res = run(middleware(validator(TokenValidation.Invalid)).protectMcp(echo), mcpRequest(bearer("bad")))
      res.status shouldBe Status.Unauthorized
      val c = challenge(res)
      c.params.get("error") shouldBe Some("invalid_token")
      c.params.get("resource_metadata") shouldBe Some(metadataUrl)
    }

    it("answers insufficient scope with 403 and the required scopes") {
      val mw = middleware(validator(TokenValidation.InsufficientScope(List("files:write", "files:delete"))))
      val res = run(mw.protectMcp(echo), mcpRequest(bearer("tok")))
      res.status shouldBe Status.Forbidden
      val c = challenge(res)
      c.scheme shouldBe "Bearer"
      c.params.get("error") shouldBe Some("insufficient_scope")
      c.params.get("scope") shouldBe Some("files:write files:delete")
      c.params.get("resource_metadata") shouldBe Some(metadataUrl)
    }

    it("passes a valid token through with the authentication attribute") {
      val res = run(middleware(validator(TokenValidation.Valid)).protectMcp(echo), mcpRequest(bearer("tok")))
      res.status shouldBe Status.Ok
      res.as[String].unsafeRunSync() should include("tok")
    }

    it("hands the bearer token to the validator") {
      val mw = middleware(t => IO.pure(if t == "good" then TokenValidation.Valid else TokenValidation.Invalid))
      run(mw.protectMcp(echo), mcpRequest(bearer("good"))).status shouldBe Status.Ok
      run(mw.protectMcp(echo), mcpRequest(bearer("other"))).status shouldBe Status.Unauthorized
    }

    it("does not protect routes outside of the mcp path") {
      val other = HttpRoutes.of[IO] { case GET -> Root / "health" => Ok("up") }
      val res = run(
        middleware(validator(TokenValidation.Invalid)).protectMcp(other),
        Request[IO](Method.GET, uri"/health").putHeaders(host),
      )
      res.status shouldBe Status.Ok
    }

    it("keeps accepting a Boolean validator") {
      val mw = OAuthMiddleware(
        name = "legacy",
        authorizationServers = Nil,
        scopes = Nil,
        validateToken = t => IO.pure(t.nonEmpty),
        root = Root,
      )
      run(mw.protectMcp(echo), mcpRequest(bearer("tok"))).status shouldBe Status.Ok
      val res = run(mw.protectMcp(echo), mcpRequest(bearer("x")))
      res.status shouldBe Status.Ok
      val denied = OAuthMiddleware("legacy", Nil, Nil, _ => IO.pure(false))
      val res2 = run(denied.protectMcp(echo), mcpRequest(bearer("tok")))
      res2.status shouldBe Status.Unauthorized
      challenge(res2).params.get("scope") shouldBe None
    }
  }

  describe("OAuthMiddleware.wellKnownRoutes") {
    val mw = middleware(validator(TokenValidation.Valid))

    def fetch(path: Uri): Json =
      val res = run(mw.wellKnownRoutes, Request[IO](Method.GET, path).putHeaders(host))
      res.status shouldBe Status.Ok
      res.as[Json].unsafeRunSync()

    it("serves the protected resource metadata per RFC 9728") {
      val json = fetch(uri"/.well-known/oauth-protected-resource")
      val c = json.hcursor
      c.get[String]("resource") shouldBe Right("http://mcp.example.com/")
      c.get[List[String]]("authorization_servers") shouldBe Right(List("https://auth.example.com"))
      c.get[List[String]]("scopes_supported") shouldBe Right(List("files:read", "openid"))
      c.get[List[String]]("bearer_methods_supported") shouldBe Right(List("header"))
      c.get[String]("resource_name") shouldBe Right("test-server")
      json.asObject.get.contains("supported_scopes") shouldBe false
    }

    it("serves the same document at the path suffixed URL") {
      fetch(uri"/.well-known/oauth-protected-resource/mcp") shouldBe fetch(uri"/.well-known/oauth-protected-resource")
    }

    it("serves the URL advertised in resource_metadata") {
      val advertised = Uri.unsafeFromString(metadataUrl)
      val path = advertised.copy(scheme = None, authority = None)
      fetch(path).hcursor.get[String]("resource_name") shouldBe Right("test-server")
    }

    it("makes relative authorization server URIs absolute") {
      val relative = OAuthMiddleware(
        name = "x",
        authorizationServers = List(uri"/"),
        scopes = Nil,
        validateToken = validator(TokenValidation.Valid),
      )
      val req = Request[IO](Method.GET, uri"/.well-known/oauth-protected-resource").putHeaders(host)
      val res = run(relative.wellKnownRoutes, req)
      res.as[Json].unsafeRunSync().hcursor.get[List[String]]("authorization_servers") shouldBe
        Right(List("http://mcp.example.com/"))
    }
  }
