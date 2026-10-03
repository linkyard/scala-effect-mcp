package ch.linkyard.mcp.example.conformance

import io.circe.JsonObject
import io.circe.parser

import java.util.Base64

/** Constants of the reference fixture (everything-server.ts of the conformance suite). */
private[conformance] object Fixtures:
  /** A 1x1 red PNG pixel. */
  val ImageBase64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="

  /** A minimal WAV file. */
  val AudioBase64 = "UklGRiYAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQIAAAA="

  def image: Array[Byte] = Base64.getDecoder.decode(ImageBase64)
  def audio: Array[Byte] = Base64.getDecoder.decode(AudioBase64)

  val StaticTextUri = "test://static-text"
  val StaticBinaryUri = "test://static-binary"
  val WatchedUri = "test://watched-resource"
  val TemplateUri = "test://template/{id}/data"
  val TemplatePrefix = "test://template/"

  private def schema(json: String): JsonObject =
    parser.parse(json).toOption.flatMap(_.asObject).getOrElse(sys.error("invalid json schema in the fixture"))

  /** A JSON schema 2020-12 with keywords that a server must not strip ($schema, $defs, $anchor, allOf, if/then/else).
    */
  val JsonSchema2020: JsonObject = schema(
    """{
      |  "$schema": "https://json-schema.org/draft/2020-12/schema",
      |  "type": "object",
      |  "$defs": {
      |    "address": {
      |      "$anchor": "addressDef",
      |      "type": "object",
      |      "properties": {"street": {"type": "string"}, "city": {"type": "string"}}
      |    }
      |  },
      |  "properties": {
      |    "name": {"type": "string"},
      |    "address": {"$ref": "#/$defs/address"},
      |    "contactMethod": {"type": "string", "enum": ["phone", "email"]},
      |    "phone": {"type": "string"},
      |    "email": {"type": "string"}
      |  },
      |  "allOf": [{"anyOf": [{"required": ["phone"]}, {"required": ["email"]}]}],
      |  "if": {"properties": {"contactMethod": {"const": "phone"}}, "required": ["contactMethod"]},
      |  "then": {"required": ["phone"]},
      |  "else": {"required": ["email"]},
      |  "additionalProperties": false
      |}""".stripMargin
  )

  /** Parameters that are mirrored into the http header Mcp-Param-Region (SEP-2243). */
  val HeaderParamsSchema: JsonObject = schema(
    """{
      |  "type": "object",
      |  "properties": {
      |    "region": {"type": "string", "description": "The region to execute the query in", "x-mcp-header": "Region"},
      |    "query": {"type": "string", "description": "The query to execute"}
      |  },
      |  "required": ["region", "query"]
      |}""".stripMargin
  )

  /** The form of an elicitation with default values for all primitive types (SEP-1034). */
  val ElicitationDefaults: JsonObject = schema(
    """{
      |  "type": "object",
      |  "properties": {
      |    "name": {"type": "string", "description": "User name", "default": "John Doe"},
      |    "age": {"type": "integer", "description": "User age", "default": 30},
      |    "score": {"type": "number", "description": "User score", "default": 95.5},
      |    "status": {
      |      "type": "string",
      |      "description": "User status",
      |      "enum": ["active", "inactive", "pending"],
      |      "default": "active"
      |    },
      |    "verified": {"type": "boolean", "description": "Verification status", "default": true}
      |  },
      |  "required": []
      |}""".stripMargin
  )

  /** The form of an elicitation with the five variants of enums (SEP-1330). */
  val ElicitationEnums: JsonObject = schema(
    """{
      |  "type": "object",
      |  "properties": {
      |    "untitledSingle": {"type": "string", "description": "Select one option", "enum": ["option1", "option2", "option3"]},
      |    "titledSingle": {
      |      "type": "string",
      |      "description": "Select one option with titles",
      |      "oneOf": [
      |        {"const": "value1", "title": "First Option"},
      |        {"const": "value2", "title": "Second Option"},
      |        {"const": "value3", "title": "Third Option"}
      |      ]
      |    },
      |    "legacyEnum": {
      |      "type": "string",
      |      "description": "Select one option (legacy)",
      |      "enum": ["opt1", "opt2", "opt3"],
      |      "enumNames": ["Option One", "Option Two", "Option Three"]
      |    },
      |    "untitledMulti": {
      |      "type": "array",
      |      "description": "Select multiple options",
      |      "minItems": 1,
      |      "maxItems": 3,
      |      "items": {"type": "string", "enum": ["option1", "option2", "option3"]}
      |    },
      |    "titledMulti": {
      |      "type": "array",
      |      "description": "Select multiple options with titles",
      |      "minItems": 1,
      |      "maxItems": 3,
      |      "items": {
      |        "anyOf": [
      |          {"const": "value1", "title": "First Choice"},
      |          {"const": "value2", "title": "Second Choice"},
      |          {"const": "value3", "title": "Third Choice"}
      |        ]
      |      }
      |    }
      |  },
      |  "required": []
      |}""".stripMargin
  )
end Fixtures
