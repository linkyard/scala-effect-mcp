package ch.linkyard.mcp.protocol

import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Codec
import ch.linkyard.mcp.protocol.Codec.fromJsonRpc
import ch.linkyard.mcp.protocol.Codec.toJsonRpc
import io.circe.literal.*
import io.circe.parser.*
import org.scalatest.EitherValues.*
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec

class CodecSpec extends AnyFunSpec with OptionValues {
  describe("Codec deserialization") {
    describe("fromJsonRpc for Requests") {
      it("should deserialize Initialize request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "initialize",
          "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {
              "roots": {
                "listChanged": true
              },
              "sampling": {},
              "experimental": {
                "feature": "test"
              }
            },
            "clientInfo": {
              "name": "TestClient",
              "version": "1.0.0"
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(1))
        assert(request.isInstanceOf[Initialize])

        val init = request.asInstanceOf[Initialize]
        assert(init.protocolVersion == "2025-06-18")
        assert(init.clientInfo.name == "TestClient")
        assert(init.clientInfo.version == "1.0.0")
      }

      it("should deserialize Ping request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": "ping-123",
          "method": "ping",
          "params": {
            "_meta": {
              "timestamp": "2024-01-01T00:00:00Z"
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdString("ping-123"))
        assert(request.isInstanceOf[Ping])
      }

      it("should deserialize Resources.ListResources request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 2,
          "method": "resources/list",
          "params": {
            "cursor": "next-page",
            "_meta": {
              "source": "test"
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(2))
        assert(request.isInstanceOf[Resources.ListResources])

        val listResources = request.asInstanceOf[Resources.ListResources]
        assert(listResources.cursor.contains("next-page"))
      }

      it("should deserialize Resources.ReadResource request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 3,
          "method": "resources/read",
          "params": {
            "uri": "file:///test.txt"
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(3))
        assert(request.isInstanceOf[Resources.ReadResource])

        val readResource = request.asInstanceOf[Resources.ReadResource]
        assert(readResource.uri == "file:///test.txt")
      }

      it("should deserialize Tools.ListTools request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 4,
          "method": "tools/list",
          "params": {
            "cursor": "tools-cursor"
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(4))
        assert(request.isInstanceOf[Tool.ListTools])

        val listTools = request.asInstanceOf[Tool.ListTools]
        assert(listTools.cursor.contains("tools-cursor"))
      }

      it("should deserialize Tools.CallTool request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 5,
          "method": "tools/call",
          "params": {
            "name": "test-tool",
            "arguments": {
              "param1": "value1",
              "param2": 42
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(5))
        assert(request.isInstanceOf[Tool.CallTool])

        val callTool = request.asInstanceOf[Tool.CallTool]
        assert(callTool.name == "test-tool")
        assert(callTool.arguments("param1").exists(_.asString.contains("value1")))
        assert(callTool.arguments("param2").exists(_.asNumber.flatMap(_.toInt).contains(42)))
      }

      it("should deserialize Prompts.ListPrompts request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 6,
          "method": "prompts/list",
          "params": {
            "cursor": "prompts-cursor"
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(6))
        assert(request.isInstanceOf[Prompts.ListPrompts])

        val listPrompts = request.asInstanceOf[Prompts.ListPrompts]
        assert(listPrompts.cursor.contains("prompts-cursor"))
      }

      it("should deserialize Prompts.GetPrompt request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 7,
          "method": "prompts/get",
          "params": {
            "name": "greeting-prompt",
            "arguments": {
              "time": "morning",
              "user": "John"
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(7))
        assert(request.isInstanceOf[Prompts.GetPrompt])

        val getPrompt = request.asInstanceOf[Prompts.GetPrompt]
        assert(getPrompt.name == "greeting-prompt")
        assert(getPrompt.arguments.contains(Map("time" -> "morning", "user" -> "John")))
      }

      it("should deserialize Logging.SetLevel request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 8,
          "method": "logging/setLevel",
          "params": {
            "level": "debug"
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(8))
        assert(request.isInstanceOf[Logging.SetLevel])

        val setLevel = request.asInstanceOf[Logging.SetLevel]
        assert(setLevel.level == LoggingLevel.Debug)
      }

      it("should deserialize Sampling.CreateMessage request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 9,
          "method": "sampling/createMessage",
          "params": {
            "messages": [
              {
                "role": "user",
                "content": {
                  "type": "text",
                  "text": "Hello"
                }
              }
            ],
            "maxTokens": 100,
            "temperature": 0.7
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(9))
        assert(request.isInstanceOf[Sampling.CreateMessage])

        val createMessage = request.asInstanceOf[Sampling.CreateMessage]
        assert(createMessage.maxTokens == 100)
        assert(createMessage.temperature.contains(0.7))
        assert(createMessage.messages.length == 1)
        assert(createMessage.messages.head.role == Role.User)
      }

      it("should deserialize Completion.Complete request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 10,
          "method": "completion/complete",
          "params": {
            "ref": {
              "name": "test-prompt",
              "title": "Test Prompt"
            },
            "argument": {
              "name": "input",
              "value": "test"
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(10))
        assert(request.isInstanceOf[Completion.Complete])

        val complete = request.asInstanceOf[Completion.Complete]
        assert(complete.argument.name == "input")
        assert(complete.argument.value == "test")
      }

      it("should deserialize Roots.ListRoots request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 11,
          "method": "roots/list",
          "params": {}
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(11))
        assert(request.isInstanceOf[Roots.ListRoots])
      }

      it("should deserialize Elicitation.Create request") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 12,
          "method": "elicitation/create",
          "params": {
            "message": "Please provide your name",
            "requestedSchema": {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string"
                }
              }
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(12))
        assert(request.isInstanceOf[Elicitation.Create])

        val create = request.asInstanceOf[Elicitation.Create]
        assert(create.message == "Please provide your name")
      }

      it("should handle unknown request method") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 13,
          "method": "unknown/method",
          "params": {}
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val deserialized = Codec.fromJsonRpc(result.value)
        assert(deserialized.isLeft)
        assert(deserialized.left.value.getMessage.contains("Unknown request method"))
      }

      it("should handle empty params") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 14,
          "method": "ping"
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val (requestId, request) = Codec.fromJsonRpc(result.value).value
        assert(requestId == RequestId.IdNumber(14))
        assert(request.isInstanceOf[Ping])
      }
    }

    describe("fromJsonRpc for Notifications") {
      it("should deserialize Initialized notification") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/initialized",
          "params": {
            "_meta": {
              "timestamp": "2024-01-01T00:00:00Z"
            }
          }
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = Codec.fromJsonRpc(result.value).value
        assert(notification.isInstanceOf[Initialized])
      }

      it("should deserialize Cancelled notification") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/cancelled",
          "params": {
            "requestId": "req-123",
            "reason": "User cancelled"
          }
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = Codec.fromJsonRpc(result.value).value
        assert(notification.isInstanceOf[Cancelled])

        val cancelled = notification.asInstanceOf[Cancelled]
        assert(cancelled.requestId == RequestId.IdString("req-123"))
        assert(cancelled.reason == "User cancelled")
      }

      it("should deserialize ProgressNotification") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/progress",
          "params": {
            "progressToken": "progress-123",
            "progress": 0.5,
            "total": 100.0,
            "message": "Processing..."
          }
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = Codec.fromJsonRpc(result.value).value
        assert(notification.isInstanceOf[ProgressNotification])

        val progress = notification.asInstanceOf[ProgressNotification]
        assert(progress.progressToken == ProgressToken.TokenString("progress-123"))
        assert(progress.progress == 0.5)
        assert(progress.total.contains(100.0))
        assert(progress.message.contains("Processing..."))
      }

      it("should deserialize Resources.Updated notification") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/resources/updated",
          "params": {
            "uri": "file:///updated.txt"
          }
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = Codec.fromJsonRpc(result.value).value
        assert(notification.isInstanceOf[Resources.Updated])

        val updated = notification.asInstanceOf[Resources.Updated]
        assert(updated.uri == "file:///updated.txt")
      }

      it("should deserialize Resources.ListChanged notification") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/resources/list_changed",
          "params": {}
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = Codec.fromJsonRpc(result.value).value
        assert(notification.isInstanceOf[Resources.ListChanged])
      }

      it("should deserialize Tool.ListChanged notification") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/tools/list_changed",
          "params": {}
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = Codec.fromJsonRpc(result.value).value
        assert(notification.isInstanceOf[Tool.ListChanged])
      }

      it("should deserialize Prompts.PromptListChanged notification") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/prompts/list_changed",
          "params": {}
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = Codec.fromJsonRpc(result.value).value
        assert(notification.isInstanceOf[Prompts.ListChanged])
      }

      it("should deserialize Roots.ListChanged notification") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/roots/list_changed",
          "params": {}
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = Codec.fromJsonRpc(result.value).value
        assert(notification.isInstanceOf[Roots.ListChanged])
      }

      it("should deserialize Logging.LoggingMessage notification") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/message",
          "params": {
            "level": "info",
            "logger": "test-logger",
            "data": {
              "message": "Test log message"
            }
          }
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = Codec.fromJsonRpc(result.value).value
        assert(notification.isInstanceOf[Logging.LoggingMessage])

        val logMessage = notification.asInstanceOf[Logging.LoggingMessage]
        assert(logMessage.level == LoggingLevel.Info)
        assert(logMessage.logger.contains("test-logger"))
      }

      it("should handle unknown notification method") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/unknown",
          "params": {}
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val deserialized = Codec.fromJsonRpc(result.value)
        assert(deserialized.isLeft)
        assert(deserialized.left.value.getMessage.contains("Unknown notification method"))
      }

      it("should handle empty params in notification") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/initialized"
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = Codec.fromJsonRpc(result.value).value
        assert(notification.isInstanceOf[Initialized])
      }
    }

    describe("fromJsonRpc for Responses") {
      it("should deserialize typed response") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "completion": {
              "values": ["test1", "test2"],
              "total": 5,
              "hasMore": true
            }
          }
        }
        """

        val result = decode[JsonRpc.Response](json.noSpaces)
        assert(result.isRight)

        val response = Codec.fromJsonRpc[Completion.Complete.Response](result.value).value
        assert(response.completion.values == List("test1", "test2"))
        assert(response.completion.total.contains(5))
        assert(response.completion.hasMore.contains(true))
      }

      it("should handle error response") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "error": {
            "code": -32601,
            "message": "Method not found"
          }
        }
        """

        val result = decode[JsonRpc.Response](json.noSpaces)
        assert(result.isRight)

        val deserialized = Codec.fromJsonRpc[Completion.Complete.Response](result.value)
        assert(deserialized.isLeft)
        assert(deserialized.left.value.getMessage.contains("JSON-RPC error"))
      }
    }

    describe("Helper methods") {
      it("should convert JsonRpc.Id to RequestId") {
        val stringId = JsonRpc.Id.IdString("test-id")
        val numberId = JsonRpc.Id.IdInt(42)

        assert(stringId.fromJsonRpc == RequestId.IdString("test-id"))
        assert(numberId.fromJsonRpc == RequestId.IdNumber(42))
      }

      it("should convert RequestId to JsonRpc.Id") {
        val stringId = RequestId.IdString("test-id")
        val numberId = RequestId.IdNumber(42)

        assert(stringId.toJsonRpc == JsonRpc.Id.IdString("test-id"))
        assert(numberId.toJsonRpc == JsonRpc.Id.IdInt(42))
      }
    }
  }
}
