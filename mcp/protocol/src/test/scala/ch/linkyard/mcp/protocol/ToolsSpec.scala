package ch.linkyard.mcp.protocol

import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Codec
import io.circe.Encoder.encodeJsonObject
import io.circe.literal.*
import io.circe.parser.*
import io.circe.syntax.*
import org.scalatest.EitherValues.*
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec

class ToolsSpec extends AnyFunSpec with OptionValues {
  describe("Tools messages") {
    describe("ListTools request") {
      it("should serialize listTools request correctly") {
        val listTools = Tool.ListTools(cursor =
          Some("cursor-123")
        )

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), listTools)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "tools/list",
          "params": {
            "cursor": "cursor-123"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize listTools request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "tools/list",
          "params": {
            "cursor": "cursor-123"
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "tools/list")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val listTools = decode[Tool.ListTools](params.asJson.noSpaces)
        assert(listTools.isRight)
        assert(listTools.value.cursor.contains("cursor-123"))
      }
    }

    describe("ListTools response") {
      it("should serialize listTools response correctly") {
        val response = Tool.ListTools.Response(
          tools = List(
            Tool(
              name = "calculator",
              title = Some("Calculator"),
              description = Some("A simple calculator tool"),
              inputSchema = io.circe.JsonObject(
                "type" -> "object".asJson,
                "properties" -> io.circe.JsonObject(
                  "expression" -> io.circe.JsonObject("type" -> "string".asJson).asJson
                ).asJson,
                "required" -> List("expression").asJson,
              ),
              outputSchema = Some(io.circe.JsonObject(
                "type" -> "object".asJson,
                "properties" -> io.circe.JsonObject(
                  "result" -> io.circe.JsonObject("type" -> "number".asJson).asJson
                ).asJson,
              )),
              annotations = Some(Tool.Annotations(
                title = Some("Calculator Tool"),
                readOnlyHint = Some(false),
                destructiveHint = Some(false),
                idempotentHint = Some(true),
                openWorldHint = Some(false),
              )),
            ),
            Tool(
              name = "file_reader",
              title = Some("File Reader"),
              description = Some("Read file contents"),
              inputSchema = io.circe.JsonObject(
                "type" -> "object".asJson,
                "properties" -> io.circe.JsonObject(
                  "path" -> io.circe.JsonObject("type" -> "string".asJson).asJson
                ).asJson,
                "required" -> List("path").asJson,
              ),
              outputSchema = None,
              annotations = None,
            ),
          ),
          nextCursor = Some("cursor-456"),
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "tools": [
              {
                "name": "calculator",
                "title": "Calculator",
                "description": "A simple calculator tool",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "expression": {
                      "type": "string"
                    }
                  },
                  "required": ["expression"]
                },
                "outputSchema": {
                  "type": "object",
                  "properties": {
                    "result": {
                      "type": "number"
                    }
                  }
                },
                "annotations": {
                  "title": "Calculator Tool",
                  "readOnlyHint": false,
                  "destructiveHint": false,
                  "idempotentHint": true,
                  "openWorldHint": false
                }
              },
              {
                "name": "file_reader",
                "title": "File Reader",
                "description": "Read file contents",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string"
                    }
                  },
                  "required": ["path"]
                }
              }
            ],
            "nextCursor": "cursor-456"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize listTools response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "tools": [
              {
                "name": "calculator",
                "title": "Calculator",
                "description": "A simple calculator tool",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "expression": {
                      "type": "string"
                    }
                  },
                  "required": ["expression"]
                },
                "outputSchema": {
                  "type": "object",
                  "properties": {
                    "result": {
                      "type": "number"
                    }
                  }
                },
                "annotations": {
                  "title": "Calculator Tool",
                  "readOnlyHint": false,
                  "destructiveHint": false,
                  "idempotentHint": true,
                  "openWorldHint": false
                }
              },
              {
                "name": "file_reader",
                "title": "File Reader",
                "description": "Read file contents",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string"
                    }
                  },
                  "required": ["path"]
                }
              }
            ],
            "nextCursor": "cursor-456"
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Tool.ListTools.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.tools.length == 2)
        assert(response.value.tools.head.name == "calculator")
        assert(response.value.tools.head.title.contains("Calculator"))
        assert(response.value.tools.head.annotations.isDefined)
        assert(response.value.tools.head.annotations.get.idempotentHint.contains(true))
        assert(response.value.nextCursor.contains("cursor-456"))
      }
    }

    describe("CallTool request") {
      it("should serialize callTool request correctly") {
        val callTool = Tool.CallTool(
          name = "calculator",
          arguments = io.circe.JsonObject(
            "expression" -> "2 + 2".asJson
          ),
        )

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), callTool)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "tools/call",
          "params": {
            "name": "calculator",
            "arguments": {
              "expression": "2 + 2"
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize callTool request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "tools/call",
          "params": {
            "name": "calculator",
            "arguments": {
              "expression": "2 + 2"
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "tools/call")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val callTool = decode[Tool.CallTool](params.asJson.noSpaces)
        assert(callTool.isRight)
        assert(callTool.value.name == "calculator")
        assert(callTool.value.arguments.asJson.hcursor.downField("expression").as[String].contains("2 + 2"))
      }
    }

    describe("CallTool response - Success") {
      it("should serialize callTool success response correctly") {
        val response = Tool.CallTool.Response.Success(
          content = List(
            Content.Text("The result of 2 + 2 is 4", None)
          ),
          structuredContent = Some(io.circe.JsonObject(
            "result" -> 4.asJson
          )),
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "content": [
              {
                "type": "text",
                "text": "The result of 2 + 2 is 4"
              }
            ],
            "structuredContent": {
              "result": 4
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize callTool success response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "content": [
              {
                "type": "text",
                "text": "The result of 2 + 2 is 4"
              }
            ],
            "structuredContent": {
              "result": 4
            }
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Tool.CallTool.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        val success = response.value.asInstanceOf[Tool.CallTool.Response.Success]
        assert(success.content.length == 1)
        assert(success.content.head.asInstanceOf[Content.Text].text == "The result of 2 + 2 is 4")
        assert(success.structuredContent.isDefined)
        assert(success.structuredContent.get.asJson.hcursor.downField("result").as[Int].contains(4))
      }
    }

    describe("CallTool response - Error") {
      it("should serialize callTool error response correctly") {
        val response = Tool.CallTool.Response.Error(content =
          List(
            Content.Text("Error: Invalid expression", None)
          )
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "content": [
              {
                "type": "text",
                "text": "Error: Invalid expression"
              }
            ],
            "isError": true
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize callTool error response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "content": [
              {
                "type": "text",
                "text": "Error: Invalid expression"
              }
            ],
            "isError": true
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Tool.CallTool.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        val error = response.value.asInstanceOf[Tool.CallTool.Response.Error]
        assert(error.content.length == 1)
        assert(error.content.head.asInstanceOf[Content.Text].text == "Error: Invalid expression")
      }
    }

    describe("ToolListChanged notification") {
      it("should serialize toolListChanged notification correctly") {
        val listChanged = Tool.ListChanged()

        val jsonRpc = Codec.encodeServerNotification(listChanged)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/tools/list_changed"
        }
        """

        assert(json == expected)
      }

      it("should deserialize toolListChanged notification correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/tools/list_changed"
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = result.value
        assert(notification.method == "notifications/tools/list_changed")
      }
    }

    describe("Tool object") {
      it("should serialize Tool object correctly") {
        val tool = Tool(
          name = "test-tool",
          title = Some("Test Tool"),
          description = Some("A test tool"),
          inputSchema = io.circe.JsonObject(
            "type" -> "object".asJson,
            "properties" -> io.circe.JsonObject(
              "input" -> io.circe.JsonObject("type" -> "string".asJson).asJson
            ).asJson,
          ),
          outputSchema = Some(io.circe.JsonObject(
            "type" -> "object".asJson,
            "properties" -> io.circe.JsonObject(
              "output" -> io.circe.JsonObject("type" -> "string".asJson).asJson
            ).asJson,
          )),
          annotations = Some(Tool.Annotations(
            title = Some("Test Tool Title"),
            readOnlyHint = Some(true),
            destructiveHint = Some(false),
            idempotentHint = Some(true),
            openWorldHint = Some(false),
          )),
        )

        val json = tool.asJson.deepDropNullValues

        val expected = json"""
        {
          "name": "test-tool",
          "title": "Test Tool",
          "description": "A test tool",
          "inputSchema": {
            "type": "object",
            "properties": {
              "input": {
                "type": "string"
              }
            }
          },
          "outputSchema": {
            "type": "object",
            "properties": {
              "output": {
                "type": "string"
              }
            }
          },
          "annotations": {
            "title": "Test Tool Title",
            "readOnlyHint": true,
            "destructiveHint": false,
            "idempotentHint": true,
            "openWorldHint": false
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize Tool object correctly") {
        val json = json"""
        {
          "name": "test-tool",
          "title": "Test Tool",
          "description": "A test tool",
          "inputSchema": {
            "type": "object",
            "properties": {
              "input": {
                "type": "string"
              }
            }
          },
          "outputSchema": {
            "type": "object",
            "properties": {
              "output": {
                "type": "string"
              }
            }
          },
          "annotations": {
            "title": "Test Tool Title",
            "readOnlyHint": true,
            "destructiveHint": false,
            "idempotentHint": true,
            "openWorldHint": false
          }
        }
        """

        val result = decode[Tool](json.noSpaces)
        assert(result.isRight)
        assert(result.value.name == "test-tool")
        assert(result.value.title.contains("Test Tool"))
        assert(result.value.annotations.isDefined)
        assert(result.value.annotations.get.readOnlyHint.contains(true))
        assert(result.value.annotations.get.idempotentHint.contains(true))
      }
    }

    describe("Tool.Annotations object") {
      it("should serialize Tool.Annotations object correctly") {
        val annotations = Tool.Annotations(
          title = Some("Test Tool"),
          readOnlyHint = Some(true),
          destructiveHint = Some(false),
          idempotentHint = Some(true),
          openWorldHint = Some(false),
        )

        val json = annotations.asJson

        val expected = json"""
        {
          "title": "Test Tool",
          "readOnlyHint": true,
          "destructiveHint": false,
          "idempotentHint": true,
          "openWorldHint": false
        }
        """

        assert(json == expected)
      }

      it("should deserialize Tool.Annotations object correctly") {
        val json = json"""
        {
          "title": "Test Tool",
          "readOnlyHint": true,
          "destructiveHint": false,
          "idempotentHint": true,
          "openWorldHint": false
        }
        """

        val result = decode[Tool.Annotations](json.noSpaces)
        assert(result.isRight)
        assert(result.value.title.contains("Test Tool"))
        assert(result.value.readOnlyHint.contains(true))
        assert(result.value.destructiveHint.contains(false))
        assert(result.value.idempotentHint.contains(true))
        assert(result.value.openWorldHint.contains(false))
      }
    }
  }
}
