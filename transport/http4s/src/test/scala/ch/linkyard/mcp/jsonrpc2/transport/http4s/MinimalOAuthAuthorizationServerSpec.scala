package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class MinimalOAuthAuthorizationServerSpec extends AnyFunSpec with Matchers:
  private def json(s: String): Json = parse(s).toOption.get

  describe("MinimalOAuthAuthorizationServer.copiedMetadata") {
    it("copies the supported fields when present upstream") {
      val upstream = json("""{
        "issuer": "https://idp.example.com",
        "authorization_response_iss_parameter_supported": true,
        "client_id_metadata_document_supported": true,
        "code_challenge_methods_supported": ["S256"],
        "jwks_uri": "https://idp.example.com/jwks"
      }""")
      Json.fromJsonObject(MinimalOAuthAuthorizationServer.copiedMetadata(upstream)) shouldBe json("""{
        "authorization_response_iss_parameter_supported": true,
        "client_id_metadata_document_supported": true,
        "code_challenge_methods_supported": ["S256"]
      }""")
    }

    it("omits fields that are missing upstream") {
      val upstream = json("""{"issuer": "https://idp.example.com", "code_challenge_methods_supported": ["S256"]}""")
      MinimalOAuthAuthorizationServer.copiedMetadata(upstream).keys.toList shouldBe
        List("code_challenge_methods_supported")
    }
  }

  describe("MinimalOAuthAuthorizationServer route") {
    it("serves the copied fields in the metadata document") {
      val upstream = json("""{"authorization_response_iss_parameter_supported": true}""")
      val server = MinimalOAuthAuthorizationServer(
        "https://idp.example.com",
        uri"https://idp.example.com/authorize",
        uri"https://idp.example.com/token",
        None,
        MinimalOAuthAuthorizationServer.copiedMetadata(upstream),
      )
      val res = server.route.orNotFound
        .run(Request[IO](Method.GET, uri"/.well-known/oauth-authorization-server"))
        .unsafeRunSync()
      res.status shouldBe Status.Ok
      val body = res.as[Json].unsafeRunSync().hcursor
      body.get[String]("issuer") shouldBe Right("https://idp.example.com")
      body.get[Boolean]("authorization_response_iss_parameter_supported") shouldBe Right(true)
    }
  }
