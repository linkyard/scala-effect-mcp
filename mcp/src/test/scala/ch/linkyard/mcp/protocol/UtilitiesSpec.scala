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

class UtilitiesSpec extends AnyFunSpec with OptionValues {
  describe("Utilities messages") {
    describe("Completion.Complete request") {
      it("should serialize completion request correctly") {
        val complete = Completion.Complete(
          ref = CompletionReference.PromptReference("greeting", Some("Greeting Prompt")),
          argument = Completion.Complete.Argument("name", "John"),
          context = Some(Completion.Complete.Context(arguments =
            Some(Map("time" -> "morning"))
          )),
        )

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), complete)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "completion/complete",
          "params": {
            "ref": {
              "name": "greeting",
              "title": "Greeting Prompt"
            },
            "argument": {
              "name": "name",
              "value": "John"
            },
            "context": {
              "arguments": {
                "time": "morning"
              }
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize completion request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "completion/complete",
          "params": {
            "ref": {
              "name": "greeting",
              "title": "Greeting Prompt"
            },
            "argument": {
              "name": "name",
              "value": "John"
            },
            "context": {
              "arguments": {
                "time": "morning"
              }
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "completion/complete")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val complete = decode[Completion.Complete](params.asJson.noSpaces)
        assert(complete.isRight)
        assert(complete.value.argument.name == "name")
        assert(complete.value.argument.value == "John")
        assert(complete.value.context.isDefined)
        assert(complete.value.context.get.arguments.contains(Map("time" -> "morning")))
      }
    }

    describe("Completion.Complete response") {
      it("should serialize completion response correctly") {
        val response = Completion.Complete.Response(completion =
          Completion(
            values = List("John", "Jane", "Jack"),
            total = Some(10),
            hasMore = Some(true),
          )
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "completion": {
              "values": ["John", "Jane", "Jack"],
              "total": 10,
              "hasMore": true
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize completion response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "completion": {
              "values": ["John", "Jane", "Jack"],
              "total": 10,
              "hasMore": true
            }
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Completion.Complete.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.completion.values == List("John", "Jane", "Jack"))
        assert(response.value.completion.total.contains(10))
        assert(response.value.completion.hasMore.contains(true))
      }
    }

    describe("Logging.SetLevel request") {
      it("should serialize setLevel request correctly") {
        val setLevel = Logging.SetLevel(level =
          LoggingLevel.Info
        )

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), setLevel)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "logging/setLevel",
          "params": {
            "level": "info"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize setLevel request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "logging/setLevel",
          "params": {
            "level": "info"
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "logging/setLevel")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val setLevel = decode[Logging.SetLevel](params.asJson.noSpaces)
        assert(setLevel.isRight)
        assert(setLevel.value.level == LoggingLevel.Info)
      }
    }

    describe("Logging.SetLevel response") {
      it("should serialize setLevel response correctly") {
        val response = Logging.SetLevel.Response()

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

      it("should deserialize setLevel response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {}
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Logging.SetLevel.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
      }
    }

    describe("Logging.LoggingMessage notification") {
      it("should serialize loggingMessage notification correctly") {
        val message = Logging.LoggingMessage(
          level = LoggingLevel.Warning,
          logger = Some("test.logger"),
          data = io.circe.Json.obj(
            "message" -> "Test warning message".asJson,
            "timestamp" -> "2023-01-01T00:00:00Z".asJson,
          ),
        )

        val jsonRpc = Codec.encodeServerNotification(message)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/message",
          "params": {
            "level": "warning",
            "logger": "test.logger",
            "data": {
              "message": "Test warning message",
              "timestamp": "2023-01-01T00:00:00Z"
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize loggingMessage notification correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/message",
          "params": {
            "level": "warning",
            "logger": "test.logger",
            "data": {
              "message": "Test warning message",
              "timestamp": "2023-01-01T00:00:00Z"
            }
          }
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = result.value
        assert(notification.method == "notifications/message")

        val params = notification.params.value
        val message = decode[Logging.LoggingMessage](params.asJson.noSpaces)
        assert(message.isRight)
        assert(message.value.level == LoggingLevel.Warning)
        assert(message.value.logger.contains("test.logger"))
        assert(message.value.data.hcursor.downField("message").as[String].contains("Test warning message"))
      }
    }

    describe("CompletionReference") {
      it("should serialize PromptReference correctly") {
        val ref = CompletionReference.PromptReference("test-prompt", Some("Test Prompt"))
        val json = ref.asJson

        val expected = json"""
        {
          "name": "test-prompt",
          "title": "Test Prompt"
        }
        """

        assert(json == expected)
      }

      it("should serialize ResourceTemplateReference correctly") {
        val ref = CompletionReference.ResourceTemplateReference("file:///path/to/template")
        val json = ref.asJson

        val expected = json"""
        {
          "uri": "file:///path/to/template"
        }
        """

        assert(json == expected)
      }

      it("should deserialize PromptReference correctly") {
        val json = json"""
        {
          "name": "test-prompt",
          "title": "Test Prompt"
        }
        """

        val result = decode[CompletionReference](json.noSpaces)
        assert(result.isRight)
        val ref = result.value.asInstanceOf[CompletionReference.PromptReference]
        assert(ref.name == "test-prompt")
        assert(ref.title.contains("Test Prompt"))
      }

      it("should deserialize ResourceTemplateReference correctly") {
        val json = json"""
        {
          "uri": "file:///path/to/template"
        }
        """

        val result = decode[CompletionReference](json.noSpaces)
        assert(result.isRight)
        val ref = result.value.asInstanceOf[CompletionReference.ResourceTemplateReference]
        assert(ref.uri == "file:///path/to/template")
      }
    }

    describe("Completion.Complete.Argument") {
      it("should serialize Argument correctly") {
        val arg = Completion.Complete.Argument("test-arg", "test-value")
        val json = arg.asJson

        val expected = json"""
        {
          "name": "test-arg",
          "value": "test-value"
        }
        """

        assert(json == expected)
      }

      it("should deserialize Argument correctly") {
        val json = json"""
        {
          "name": "test-arg",
          "value": "test-value"
        }
        """

        val result = decode[Completion.Complete.Argument](json.noSpaces)
        assert(result.isRight)
        assert(result.value.name == "test-arg")
        assert(result.value.value == "test-value")
      }
    }

    describe("Completion.Complete.Context") {
      it("should serialize Context correctly") {
        val ctx = Completion.Complete.Context(arguments =
          Some(Map("arg1" -> "value1", "arg2" -> "value2"))
        )
        val json = ctx.asJson

        val expected = json"""
        {
          "arguments": {
            "arg1": "value1",
            "arg2": "value2"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize Context correctly") {
        val json = json"""
        {
          "arguments": {
            "arg1": "value1",
            "arg2": "value2"
          }
        }
        """

        val result = decode[Completion.Complete.Context](json.noSpaces)
        assert(result.isRight)
        assert(result.value.arguments.contains(Map("arg1" -> "value1", "arg2" -> "value2")))
      }
    }

    describe("Completion.Complete.Completion") {
      it("should serialize Completion correctly") {
        val completion = Completion(
          values = List("value1", "value2", "value3"),
          total = Some(5),
          hasMore = Some(true),
        )
        val json = completion.asJson

        val expected = json"""
        {
          "values": ["value1", "value2", "value3"],
          "total": 5,
          "hasMore": true
        }
        """

        assert(json == expected)
      }

      it("should deserialize Completion correctly") {
        val json = json"""
        {
          "values": ["value1", "value2", "value3"],
          "total": 5,
          "hasMore": true
        }
        """

        val result = decode[Completion](json.noSpaces)
        assert(result.isRight)
        assert(result.value.values == List("value1", "value2", "value3"))
        assert(result.value.total.contains(5))
        assert(result.value.hasMore.contains(true))
      }
    }

    describe("LoggingLevel enum") {
      it("should serialize LoggingLevel values correctly") {
        assert(LoggingLevel.Debug.asJson == "debug".asJson)
        assert(LoggingLevel.Info.asJson == "info".asJson)
        assert(LoggingLevel.Notice.asJson == "notice".asJson)
        assert(LoggingLevel.Warning.asJson == "warning".asJson)
        assert(LoggingLevel.Error.asJson == "error".asJson)
        assert(LoggingLevel.Critical.asJson == "critical".asJson)
        assert(LoggingLevel.Alert.asJson == "alert".asJson)
        assert(LoggingLevel.Emergency.asJson == "emergency".asJson)
      }

      it("should deserialize LoggingLevel values correctly") {
        assert(decode[LoggingLevel]("\"debug\"").value == LoggingLevel.Debug)
        assert(decode[LoggingLevel]("\"info\"").value == LoggingLevel.Info)
        assert(decode[LoggingLevel]("\"notice\"").value == LoggingLevel.Notice)
        assert(decode[LoggingLevel]("\"warning\"").value == LoggingLevel.Warning)
        assert(decode[LoggingLevel]("\"error\"").value == LoggingLevel.Error)
        assert(decode[LoggingLevel]("\"critical\"").value == LoggingLevel.Critical)
        assert(decode[LoggingLevel]("\"alert\"").value == LoggingLevel.Alert)
        assert(decode[LoggingLevel]("\"emergency\"").value == LoggingLevel.Emergency)
      }

      it("should handle unknown logging level values") {
        val result = decode[LoggingLevel]("\"unknown\"")
        assert(result.isLeft)
      }
    }
  }
}
