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

class PromptsSpec extends AnyFunSpec with OptionValues {
  describe("Prompts messages") {
    describe("ListPrompts request") {
      it("should serialize listPrompts request correctly") {
        val listPrompts = Prompts.ListPrompts(cursor =
          Some("cursor-123")
        )

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), listPrompts)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "prompts/list",
          "params": {
            "cursor": "cursor-123"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize listPrompts request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "prompts/list",
          "params": {
            "cursor": "cursor-123"
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "prompts/list")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val listPrompts = decode[Prompts.ListPrompts](params.asJson.noSpaces)
        assert(listPrompts.isRight)
        assert(listPrompts.value.cursor.contains("cursor-123"))
      }
    }

    describe("ListPrompts response") {
      it("should serialize listPrompts response correctly") {
        val response = Prompts.ListPrompts.Response(
          prompts = List(
            Prompt(
              name = "greeting",
              title = Some("Greeting Prompt"),
              description = Some("A prompt for greeting users"),
              arguments = Some(List(
                PromptArgument(
                  name = "name",
                  title = Some("User Name"),
                  description = Some("The name of the user to greet"),
                  required = Some(true),
                )
              )),
            ),
            Prompt(
              name = "farewell",
              title = Some("Farewell Prompt"),
              description = Some("A prompt for saying goodbye"),
              arguments = None,
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
            "prompts": [
              {
                "name": "greeting",
                "title": "Greeting Prompt",
                "description": "A prompt for greeting users",
                "arguments": [
                  {
                    "name": "name",
                    "title": "User Name",
                    "description": "The name of the user to greet",
                    "required": true
                  }
                ]
              },
              {
                "name": "farewell",
                "title": "Farewell Prompt",
                "description": "A prompt for saying goodbye"
              }
            ],
            "nextCursor": "cursor-456"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize listPrompts response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "prompts": [
              {
                "name": "greeting",
                "title": "Greeting Prompt",
                "description": "A prompt for greeting users",
                "arguments": [
                  {
                    "name": "name",
                    "title": "User Name",
                    "description": "The name of the user to greet",
                    "required": true
                  }
                ]
              },
              {
                "name": "farewell",
                "title": "Farewell Prompt",
                "description": "A prompt for saying goodbye"
              }
            ],
            "nextCursor": "cursor-456"
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Prompts.ListPrompts.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.prompts.length == 2)
        assert(response.value.prompts.head.name == "greeting")
        assert(response.value.prompts.head.title.contains("Greeting Prompt"))
        assert(response.value.nextCursor.contains("cursor-456"))
      }
    }

    describe("GetPrompt request") {
      it("should serialize getPrompt request correctly") {
        val getPrompt = Prompts.GetPrompt(
          name = "greeting",
          arguments = Some(Map(
            "name" -> "John",
            "time" -> "morning",
          )),
        )

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), getPrompt)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "prompts/get",
          "params": {
            "name": "greeting",
            "arguments": {
              "name": "John",
              "time": "morning"
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize getPrompt request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "prompts/get",
          "params": {
            "name": "greeting",
            "arguments": {
              "name": "John",
              "time": "morning"
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "prompts/get")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val getPrompt = decode[Prompts.GetPrompt](params.asJson.noSpaces)
        assert(getPrompt.isRight)
        assert(getPrompt.value.name == "greeting")
        assert(getPrompt.value.arguments.contains(Map("name" -> "John", "time" -> "morning")))
      }
    }

    describe("GetPrompt response") {
      it("should serialize getPrompt response correctly") {
        val response = Prompts.GetPrompt.Response(
          description = Some("A prompt for greeting users"),
          messages = List(
            PromptMessage(
              role = Role.Assistant,
              content = Content.Text("You are a helpful assistant.", None),
            ),
            PromptMessage(
              role = Role.User,
              content = Content.Text("Hello {{name}}, good {{time}}!", None),
            ),
          ),
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "description": "A prompt for greeting users",
            "messages": [
              {
                "role": "assistant",
                "content": {
                  "type": "text",
                  "text": "You are a helpful assistant."
                }
              },
              {
                "role": "user",
                "content": {
                  "type": "text",
                  "text": "Hello {{name}}, good {{time}}!"
                }
              }
            ]
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize getPrompt response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "description": "A prompt for greeting users",
            "messages": [
              {
                "role": "assistant",
                "content": {
                  "type": "text",
                  "text": "You are a helpful assistant."
                }
              },
              {
                "role": "user",
                "content": {
                  "type": "text",
                  "text": "Hello {{name}}, good {{time}}!"
                }
              }
            ]
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Prompts.GetPrompt.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.description.contains("A prompt for greeting users"))
        assert(response.value.messages.length == 2)
        assert(response.value.messages.head.role == Role.Assistant)
        assert(response.value.messages(1).role == Role.User)
      }
    }

    describe("PromptListChanged notification") {
      it("should serialize promptListChanged notification correctly") {
        val listChanged = Prompts.ListChanged()

        val jsonRpc = Codec.encodeServerNotification(listChanged)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/prompts/list_changed"
        }
        """

        assert(json == expected)
      }

      it("should deserialize promptListChanged notification correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/prompts/list_changed"
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = result.value
        assert(notification.method == "notifications/prompts/list_changed")
      }
    }

    describe("Prompt object") {
      it("should serialize Prompt object correctly") {
        val prompt = Prompt(
          name = "test-prompt",
          title = Some("Test Prompt"),
          description = Some("A test prompt"),
          arguments = Some(List(
            PromptArgument(
              name = "arg1",
              title = Some("Argument 1"),
              description = Some("First argument"),
              required = Some(true),
            )
          )),
        )

        val json = prompt.asJson

        val expected = json"""
        {
          "name": "test-prompt",
          "title": "Test Prompt",
          "description": "A test prompt",
          "arguments": [
            {
              "name": "arg1",
              "title": "Argument 1",
              "description": "First argument",
              "required": true
            }
          ]
        }
        """

        assert(json == expected)
      }

      it("should deserialize Prompt object correctly") {
        val json = json"""
        {
          "name": "test-prompt",
          "title": "Test Prompt",
          "description": "A test prompt",
          "arguments": [
            {
              "name": "arg1",
              "title": "Argument 1",
              "description": "First argument",
              "required": true
            }
          ]
        }
        """

        val result = decode[Prompt](json.noSpaces)
        assert(result.isRight)
        assert(result.value.name == "test-prompt")
        assert(result.value.title.contains("Test Prompt"))
        assert(result.value.arguments.isDefined)
        assert(result.value.arguments.get.head.name == "arg1")
      }
    }

    describe("PromptArgument object") {
      it("should serialize PromptArgument object correctly") {
        val arg = PromptArgument(
          name = "test-arg",
          title = Some("Test Argument"),
          description = Some("A test argument"),
          required = Some(false),
        )

        val json = arg.asJson

        val expected = json"""
        {
          "name": "test-arg",
          "title": "Test Argument",
          "description": "A test argument",
          "required": false
        }
        """

        assert(json == expected)
      }

      it("should deserialize PromptArgument object correctly") {
        val json = json"""
        {
          "name": "test-arg",
          "title": "Test Argument",
          "description": "A test argument",
          "required": false
        }
        """

        val result = decode[PromptArgument](json.noSpaces)
        assert(result.isRight)
        assert(result.value.name == "test-arg")
        assert(result.value.title.contains("Test Argument"))
        assert(result.value.required.contains(false))
      }
    }

    describe("PromptMessage object") {
      it("should serialize PromptMessage object correctly") {
        val message = PromptMessage(
          role = Role.User,
          content = Content.Text("Hello world", None),
        )

        val json = message.asJson

        val expected = json"""
        {
          "role": "user",
          "content": {
            "type": "text",
            "text": "Hello world"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize PromptMessage object correctly") {
        val json = json"""
        {
          "role": "user",
          "content": {
            "type": "text",
            "text": "Hello world"
          }
        }
        """

        val result = decode[PromptMessage](json.noSpaces)
        assert(result.isRight)
        assert(result.value.role == Role.User)
        assert(result.value.content.asInstanceOf[Content.Text].text == "Hello world")
      }
    }
  }
}
