package ch.linkyard.mcp.protocol

import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Codec
import io.circe.literal.*
import io.circe.parser.*
import io.circe.syntax.*
import org.scalatest.EitherValues.*
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec

class LifecycleSpec extends AnyFunSpec with OptionValues {
  describe("Lifecycle messages") {
    describe("Initialize request") {
      it("should serialize initialize request correctly") {
        val initialize = Initialize(
          capabilities = Initialize.ClientCapabilities(
            roots = Some(Initialize.Capabilities.Changable(listChanged = true)),
            sampling = Some(Initialize.Capabilities.Supported()),
            elicitation = None,
            experimental = None,
          ),
          clientInfo = Initialize.PartyInfo(
            name = "test-client",
            version = "1.0.0",
          ),
          protocolVersion = "2025-06-18",
        )

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), initialize)
        val json = jsonRpc.asJson

        val expected = json"""
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
              "sampling": {}
            },
            "clientInfo": {
              "name": "test-client",
              "version": "1.0.0"
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize initialize request correctly") {
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
              "sampling": {}
            },
            "clientInfo": {
              "name": "test-client",
              "version": "1.0.0"
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "initialize")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val initialize = decode[Initialize](params.asJson.noSpaces)
        assert(initialize.isRight)
        assert(initialize.value.protocolVersion == "2025-06-18")
        assert(initialize.value.clientInfo.name == "test-client")
      }
    }

    describe("Initialize response") {
      it("should serialize initialize response correctly") {
        val response = Initialize.Response(
          serverInfo = Initialize.PartyInfo(
            name = "ExampleServer",
            version = "1.0.0",
          ),
          capabilities = Initialize.ServerCapabilities(
            prompts = Some(Initialize.Capabilities.Changable(listChanged = true)),
            resources = Some(Initialize.Capabilities.Subscribable(subscribe = true, listChanged = true)),
            tools = Some(Initialize.Capabilities.Changable(listChanged = true)),
            logging = Some(Initialize.Capabilities.Supported()),
            completions = None,
            experimental = None,
          ),
          instructions = Some("Optional instructions for the client"),
          protocolVersion = "2025-06-18",
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "protocolVersion": "2025-06-18",
            "serverInfo": {
              "name": "ExampleServer",
              "version": "1.0.0"
            },
            "capabilities": {
              "prompts": {
                "listChanged": true
              },
              "resources": {
                "subscribe": true,
                "listChanged": true
              },
              "tools": {
                "listChanged": true
              },
              "logging": {}
            },

            "instructions": "Optional instructions for the client"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize initialize response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "protocolVersion": "2025-06-18",
            "serverInfo": {
              "name": "test-server",
              "version": "1.0.0"
            },
            "capabilities": {
              "roots": {
                "listChanged": true
              },
              "sampling": {}
            },
            "instructions": "Welcome to the MCP server"
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Initialize.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.serverInfo.name == "test-server")
        assert(response.value.instructions.contains("Welcome to the MCP server"))
      }
    }

    describe("Ping request") {
      it("should serialize ping request correctly") {
        val ping = Ping()

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), ping)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "ping"
        }
        """

        assert(json == expected)
      }

      it("should deserialize ping request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "ping",
          "params": {}
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "ping")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val ping = decode[Ping](params.asJson.noSpaces)
        assert(ping.isRight)
      }
    }

    describe("Ping response") {
      it("should serialize ping response correctly") {
        val response = Ping.Response()

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {}
        }
        """

        assert(json == expected)
      }

      it("should deserialize ping response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {}
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Ping.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
      }
    }

    describe("Cancelled notification") {
      it("should serialize cancelled notification correctly") {
        val cancelled = Cancelled(
          requestId = RequestId.IdNumber(1),
          reason = "User cancelled the operation",
        )

        val jsonRpc = Codec.encodeServerNotification(cancelled)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/cancelled",
          "params": {
            "requestId": 1,
            "reason": "User cancelled the operation"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize cancelled notification correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/cancelled",
          "params": {
            "requestId": 1,
            "reason": "User cancelled the operation"
          }
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = result.value
        assert(notification.method == "notifications/cancelled")

        val params = notification.params.value
        val cancelled = decode[Cancelled](params.asJson.noSpaces)
        assert(cancelled.isRight)
        assert(cancelled.value.reason == "User cancelled the operation")
      }
    }

    describe("Progress notification") {
      it("should serialize progress notification correctly") {
        val progress = ProgressNotification(
          progressToken = ProgressToken.TokenString("progress-123"),
          progress = 0.5,
          total = Some(100.0),
          message = Some("Processing..."),
        )

        val jsonRpc = Codec.encodeServerNotification(progress)
        val json = jsonRpc.asJson

        val expected = json"""
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

        assert(json == expected)
      }

      it("should deserialize progress notification correctly") {
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

        val notification = result.value
        assert(notification.method == "notifications/progress")

        val params = notification.params.value
        val progress = decode[ProgressNotification](params.asJson.noSpaces)
        assert(progress.isRight)
        assert(progress.value.progress == 0.5)
        assert(progress.value.total.contains(100.0))
        assert(progress.value.message.contains("Processing..."))
      }
    }

    describe("Initialized notification") {
      it("should serialize initialized notification correctly") {
        val initialized = Initialized()

        val jsonRpc = Codec.encodeClientNotification(initialized)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/initialized"
        }
        """

        assert(json == expected)
      }

      it("should serialize initialized notification with meta correctly") {
        val initialized = Initialized(_meta = Meta("custom" -> "value".asJson))

        val jsonRpc = Codec.encodeClientNotification(initialized)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/initialized",
          "params": {
            "_meta": {
              "custom": "value"
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize initialized notification correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/initialized",
          "params": {}
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = result.value
        assert(notification.method == "notifications/initialized")

        val params = notification.params.value
        val initialized = decode[Initialized](params.asJson.noSpaces)
        assert(initialized.isRight)
      }

      it("should deserialize initialized notification with meta correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/initialized",
          "params": {
            "_meta": {
              "custom": "value"
            }
          }
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = result.value
        assert(notification.method == "notifications/initialized")

        val params = notification.params.value
        val initialized = decode[Initialized](params.asJson.noSpaces)
        assert(initialized.isRight)
        assert(initialized.value._meta.get("custom").flatMap(_.asString).contains("value"))
      }
    }
  }
}
