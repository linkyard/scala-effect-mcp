package ch.linkyard.mcp.server

import ch.linkyard.mcp.jsonrpc2.Authentication
import io.circe.literal.*
import org.scalatest.EitherValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.DurationInt

class RequestStateSpec extends AnyFunSpec with Matchers with EitherValues:
  private val key1 = Array.fill(32)(1.toByte)
  private val key2 = Array.fill(32)(2.toByte)
  private val now = Instant.parse("2026-10-03T12:00:00Z")
  private val binding = StateBinding("user", "tools/call", "tool", StateBinding.hash(json"""{"a": 1}"""))
  private val protector = RequestStateProtector(RequestStateConfig(List(key1), 5.minutes))

  describe("RequestStateProtector") {
    it("should give back the state") {
      protector.verify(protector.protect("the state", binding, now), binding, now) shouldBe Right("the state")
    }

    it("should not hand out the state in plain text for the clients that do not look") {
      protector.protect("secret", binding, now) should not include "secret"
    }

    it("should accept the state until it expires") {
      val token = protector.protect("s", binding, now)
      protector.verify(token, binding, now.plusSeconds(299)).isRight shouldBe true
      protector.verify(token, binding, now.plusSeconds(301)).left.value shouldBe "Request state expired"
    }

    it("should reject a state of another user, method, target or arguments") {
      val token = protector.protect("s", binding, now)
      protector.verify(token, binding.copy(principal = "other"), now).isLeft shouldBe true
      protector.verify(token, binding.copy(method = "prompts/get"), now).isLeft shouldBe true
      protector.verify(token, binding.copy(target = "other"), now).isLeft shouldBe true
      protector.verify(
        token,
        binding.copy(argumentsHash = StateBinding.hash(json"""{"a": 2}""")),
        now,
      ).isLeft shouldBe true
    }

    it("should reject a state that was changed") {
      val token = protector.protect("s", binding, now)
      val Array(payload, mac) = token.split('.')
      protector.verify(payload.reverse + "." + mac, binding, now).left.value shouldBe "Invalid request state"
      protector.verify(payload + "." + mac.reverse, binding, now).left.value shouldBe "Invalid request state"
      protector.verify(payload, binding, now).left.value shouldBe "Invalid request state"
      protector.verify("", binding, now).left.value shouldBe "Invalid request state"
      protector.verify("...", binding, now).left.value shouldBe "Invalid request state"
      protector.verify("%%%.%%%", binding, now).left.value shouldBe "Invalid request state"
    }

    it("should reject a state that was signed with another key") {
      val other = RequestStateProtector(RequestStateConfig(List(key2)))
      protector.verify(other.protect("s", binding, now), binding, now).left.value shouldBe "Invalid request state"
    }

    it("should accept old keys when keys are rotated") {
      val old = RequestStateProtector(RequestStateConfig(List(key2)))
      val rotated = RequestStateProtector(RequestStateConfig(List(key1, key2)))
      rotated.verify(old.protect("s", binding, now), binding, now) shouldBe Right("s")
      // new states are signed with the first key
      protector.verify(rotated.protect("t", binding, now), binding, now) shouldBe Right("t")
    }

    it("should hash arguments independent of the order of the fields") {
      StateBinding.hash(json"""{"a": 1, "b": 2}""") shouldBe StateBinding.hash(json"""{"b": 2, "a": 1}""")
      StateBinding.hash(json"""{"a": 1}""") should not be StateBinding.hash(json"""{"a": 2}""")
    }
  }

  describe("RequestStateConfig") {
    it("should need keys") {
      an[IllegalArgumentException] should be thrownBy RequestStateConfig(Nil)
      an[IllegalArgumentException] should be thrownBy RequestStateConfig(List(Array.fill(8)(1.toByte)))
    }

    it("should identify the user by the token") {
      RequestStateConfig.defaultPrincipal(Authentication.Anonymous) shouldBe "anonymous"
      val a = RequestStateConfig.defaultPrincipal(Authentication.BearerToken("a"))
      a should startWith("bearer:")
      a should not include "a" * 10
      a shouldBe RequestStateConfig.defaultPrincipal(Authentication.BearerToken("a"))
      a should not be RequestStateConfig.defaultPrincipal(Authentication.BearerToken("b"))
    }

    it("should generate different random keys") {
      RequestStateConfig.random().keys.head should not be RequestStateConfig.random().keys.head
    }
  }
