package ch.linkyard.mcp.server

import io.circe.literal.*
import org.scalatest.EitherValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class McpHeaderAnnotationsSpec extends AnyFunSpec with Matchers with EitherValues:
  private def validate(schema: io.circe.Json) = McpHeaderAnnotations.validate(schema.asObject.get)

  describe("McpHeaderAnnotations.validate") {
    it("should accept schemas without annotations") {
      validate(json"""{"type": "object", "properties": {"a": {"type": "string"}}}""").value shouldBe Nil
      validate(json"""{"type": "object"}""").value shouldBe Nil
    }

    it("should return the annotations of the specification example") {
      validate(json"""{
        "type": "object",
        "properties": {
          "region": {"type": "string", "x-mcp-header": "Region"},
          "query": {"type": "string"}
        }
      }""").value shouldBe List("Region")
    }

    it("should accept integer and boolean parameters and nested objects") {
      validate(json"""{
        "type": "object",
        "properties": {
          "count": {"type": "integer", "x-mcp-header": "Count"},
          "dry": {"type": "boolean", "x-mcp-header": "Dry"},
          "nested": {"type": "object", "properties": {"id": {"type": "string", "x-mcp-header": "Id"}}}
        }
      }""").value.toSet shouldBe Set("Count", "Dry", "Id")
    }

    it("should reject number parameters") {
      validate(
        json"""{"type": "object", "properties": {"n": {"type": "number", "x-mcp-header": "N"}}}"""
      ).isLeft shouldBe true
    }

    it("should reject parameters of other types") {
      validate(
        json"""{"type": "object", "properties": {"a": {"type": "array", "x-mcp-header": "A"}}}"""
      ).isLeft shouldBe true
      validate(json"""{"type": "object", "properties": {"a": {"x-mcp-header": "A"}}}""").isLeft shouldBe true
    }

    it("should reject empty names and names that are not valid header names") {
      for name <- List("", "has space", "colon:", "new\nline", "ünicode") do
        validate(
          json"""{"type": "object", "properties": {"a": {"type": "string", "x-mcp-header": $name}}}"""
        ).isLeft shouldBe true
    }

    it("should reject names that are not strings") {
      validate(
        json"""{"type": "object", "properties": {"a": {"type": "string", "x-mcp-header": 5}}}"""
      ).isLeft shouldBe true
    }

    it("should reject names that are used twice independent of the case") {
      validate(json"""{
        "type": "object",
        "properties": {
          "a": {"type": "string", "x-mcp-header": "Name"},
          "b": {"type": "string", "x-mcp-header": "name"}
        }
      }""").left.value should include("more than once")
    }

    it("should reject annotations that are not reachable through properties") {
      validate(json"""{
        "type": "object",
        "properties": {"list": {"type": "array", "items": {"type": "string", "x-mcp-header": "Item"}}}
      }""").isLeft shouldBe true
      validate(json"""{
        "type": "object",
        "anyOf": [{"properties": {"a": {"type": "string", "x-mcp-header": "A"}}}]
      }""").isLeft shouldBe true
      validate(json"""{
        "type": "object",
        "$$defs": {"x": {"type": "string", "x-mcp-header": "X"}},
        "properties": {"a": {"$$ref": "#/$$defs/x"}}
      }""").isLeft shouldBe true
    }

    it("should not mistake a property with the name of the keyword for an annotation") {
      validate(json"""{"type": "object", "properties": {"x-mcp-header": {"type": "string"}}}""").value shouldBe Nil
    }
  }

  describe("McpHeaderAnnotations.validateRequest") {
    val schema = json"""{
      "type": "object",
      "properties": {
        "flag": {"type": "boolean", "x-mcp-header": "Flag"},
        "nested": {"type": "object", "properties": {"id": {"type": "integer", "x-mcp-header": "Id"}}}
      }
    }""".asObject.get
    def check(arguments: io.circe.Json, headers: (String, String)*) =
      McpHeaderAnnotations.validateRequest(schema, arguments.asObject.get, headers.toMap)

    it("should find the value of nested parameters by their path") {
      check(json"""{"nested": {"id": 7}}""", "mcp-param-id" -> "7").isRight shouldBe true
      check(json"""{"nested": {"id": 7}}""", "mcp-param-id" -> "8").isLeft shouldBe true
      check(json"""{"nested": {"id": 7}}""").isLeft shouldBe true
    }

    it("should compare booleans as lower case words") {
      check(json"""{"flag": true}""", "mcp-param-flag" -> "true").isRight shouldBe true
      check(json"""{"flag": true}""", "mcp-param-flag" -> "True").isLeft shouldBe true
      check(json"""{"flag": false}""", "mcp-param-flag" -> "false").isRight shouldBe true
    }

    it("should not expect headers for absent and null values") {
      check(json"""{}""").isRight shouldBe true
      check(json"""{"flag": null}""").isRight shouldBe true
    }
  }
