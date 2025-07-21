package ch.linkyard.mcp.protocol

import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Codec
import ch.linkyard.mcp.protocol.Sampling.StopReason
import io.circe.literal.*
import io.circe.parser.*
import io.circe.syntax.*
import org.scalatest.EitherValues.*
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec

class SamplingSpec extends AnyFunSpec with OptionValues {
  describe("Sampling messages") {
    describe("CreateMessage request") {
      it("should serialize createMessage request correctly") {
        val createMessage = Sampling.CreateMessage(
          messages = List(
            Sampling.Message(
              role = Role.User,
              content = Content.Text("Hello, how are you?", None),
            )
          ),
          modelPreferences = Some(Sampling.ModelPreferences(
            hints = Some(List(Sampling.ModelHint(Some("gpt-4")))),
            costPriority = Some(0.5),
            intelligencePriority = Some(0.8),
            speedPriority = Some(0.3),
          )),
          systemPrompt = Some("You are a helpful assistant."),
          maxTokens = 100,
          temperature = Some(0.7),
          includeContext = Some("thisServer"),
          stopSequences = Some(List("END", "STOP")),
          metadata = Some(io.circe.JsonObject("sessionId" -> "123".asJson)),
        )

        val jsonRpc = Codec.encodeServerRequest(RequestId.IdNumber(1), createMessage)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "sampling/createMessage",
          "params": {
            "messages": [
              {
                "role": "user",
                "content": {
                  "type": "text",
                  "text": "Hello, how are you?"
                }
              }
            ],
            "modelPreferences": {
              "hints": [
                {
                  "name": "gpt-4"
                }
              ],
              "costPriority": 0.5,
              "intelligencePriority": 0.8,
              "speedPriority": 0.3
            },
            "systemPrompt": "You are a helpful assistant.",
            "maxTokens": 100,
            "temperature": 0.7,
            "includeContext": "thisServer",
            "stopSequences": ["END", "STOP"],
            "metadata": {
              "sessionId": "123"
            }
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize createMessage request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "sampling/createMessage",
          "params": {
            "messages": [
              {
                "role": "user",
                "content": {
                  "type": "text",
                  "text": "Hello, how are you?"
                }
              }
            ],
            "modelPreferences": {
              "hints": [
                {
                  "name": "gpt-4"
                }
              ],
              "costPriority": 0.5,
              "intelligencePriority": 0.8,
              "speedPriority": 0.3
            },
            "systemPrompt": "You are a helpful assistant.",
            "maxTokens": 100,
            "temperature": 0.7,
            "includeContext": "thisServer",
            "stopSequences": ["END", "STOP"],
            "metadata": {
              "sessionId": "123"
            }
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "sampling/createMessage")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val createMessage = decode[Sampling.CreateMessage](params.asJson.noSpaces)
        assert(createMessage.isRight)
        assert(createMessage.value.maxTokens == 100)
        assert(createMessage.value.systemPrompt.contains("You are a helpful assistant."))
        assert(createMessage.value.messages.head.role == Role.User)
      }
    }

    describe("CreateMessage response") {
      it("should serialize createMessage response correctly") {
        val response = Sampling.CreateMessage.Response(
          role = Role.Assistant,
          content = Content.Text("I'm doing well, thank you for asking!", None),
          model = "gpt-4",
          stopReason = Some(Sampling.StopReason.EndTurn),
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "role": "assistant",
            "content": {
              "type": "text",
              "text": "I'm doing well, thank you for asking!"
            },
            "model": "gpt-4",
            "stopReason": "endTurn"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize createMessage response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "role": "assistant",
            "content": {
              "type": "text",
              "text": "I'm doing well, thank you for asking!"
            },
            "model": "gpt-4",
            "stopReason": "endTurn"
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Sampling.CreateMessage.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.role == Role.Assistant)
        assert(response.value.model == "gpt-4")
        assert(response.value.stopReason.contains(Sampling.StopReason.EndTurn))
      }
    }

    describe("Message object") {
      it("should serialize Message object correctly") {
        val message = Sampling.Message(
          role = Role.User,
          content = Content.Text("Hello world", None),
        )

        val json = message.asJson.deepDropNullValues

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

      it("should deserialize Message object correctly") {
        val json = json"""
        {
          "role": "user",
          "content": {
            "type": "text",
            "text": "Hello world"
          }
        }
        """

        val result = decode[Sampling.Message](json.noSpaces)
        assert(result.isRight)
        assert(result.value.role == Role.User)
        assert(result.value.content.asInstanceOf[Content.Text].text == "Hello world")
      }
    }

    describe("ModelPreferences object") {
      it("should serialize ModelPreferences object correctly") {
        val prefs = Sampling.ModelPreferences(
          hints = Some(List(Sampling.ModelHint(Some("gpt-4")))),
          costPriority = Some(0.5),
          intelligencePriority = Some(0.8),
          speedPriority = Some(0.3),
        )

        val json = prefs.asJson

        val expected = json"""
        {
          "hints": [
            {
              "name": "gpt-4"
            }
          ],
          "costPriority": 0.5,
          "intelligencePriority": 0.8,
          "speedPriority": 0.3
        }
        """

        assert(json == expected)
      }

      it("should deserialize ModelPreferences object correctly") {
        val json = json"""
        {
          "hints": [
            {
              "name": "gpt-4"
            }
          ],
          "costPriority": 0.5,
          "intelligencePriority": 0.8,
          "speedPriority": 0.3
        }
        """

        val result = decode[Sampling.ModelPreferences](json.noSpaces)
        assert(result.isRight)
        assert(result.value.costPriority.contains(0.5))
        assert(result.value.intelligencePriority.contains(0.8))
        assert(result.value.speedPriority.contains(0.3))
        assert(result.value.hints.isDefined)
        assert(result.value.hints.get.head.name.contains("gpt-4"))
      }
    }

    describe("ModelHint object") {
      it("should serialize ModelHint object correctly") {
        val hint = Sampling.ModelHint(Some("gpt-4"))

        val json = hint.asJson

        val expected = json"""
        {
          "name": "gpt-4"
        }
        """

        assert(json == expected)
      }

      it("should deserialize ModelHint object correctly") {
        val json = json"""
        {
          "name": "gpt-4"
        }
        """

        val result = decode[Sampling.ModelHint](json.noSpaces)
        assert(result.isRight)
        assert(result.value.name.contains("gpt-4"))
      }
    }

    describe("StopReason enum") {
      it("should serialize StopReason values correctly") {
        assert(Sampling.StopReason.EndTurn.asJson == "endTurn".asJson)
        assert(Sampling.StopReason.StopSequence.asJson == "stopSequence".asJson)
        assert(Sampling.StopReason.MaxTokens.asJson == "maxTokens".asJson)
        assert((Sampling.StopReason.Other("custom"): Sampling.StopReason).asJson == "custom".asJson)
      }

      it("should deserialize StopReason values correctly") {
        assert(decode[Sampling.StopReason]("\"endTurn\"").value == Sampling.StopReason.EndTurn)
        assert(decode[Sampling.StopReason]("\"stopSequence\"").value == Sampling.StopReason.StopSequence)
        assert(decode[Sampling.StopReason]("\"maxTokens\"").value == Sampling.StopReason.MaxTokens)
        assert(decode[Sampling.StopReason]("\"custom\"").value == Sampling.StopReason.Other("custom"))
      }
    }
  }
}
