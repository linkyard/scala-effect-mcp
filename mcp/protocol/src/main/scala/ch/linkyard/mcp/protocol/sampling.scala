package ch.linkyard.mcp.protocol

import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

object Sampling:
  case class CreateMessage(
    messages: List[Message],
    modelPreferences: Option[ModelPreferences],
    systemPrompt: Option[String],
    /** The maximum number of tokens to sample, as requested by the server. The client MAY choose to sample fewer tokens
      * than requested.
      */
    maxTokens: Int,
    temperature: Option[Double],
    includeContext: Option[String],
    stopSequences: Option[List[String]],
    metadata: Option[JsonObject],
    _meta: Option[JsonObject] = None,
  ) extends Request:
    override type Response = CreateMessage.Response
    override val method: RequestMethod = RequestMethod.CreateMessage

  object CreateMessage:
    given Encoder.AsObject[CreateMessage] = Encoder.AsObject.instance { createMessage =>
      JsonObject(
        "messages" -> createMessage.messages.asJson,
        "modelPreferences" -> createMessage.modelPreferences.asJson,
        "systemPrompt" -> createMessage.systemPrompt.asJson,
        "maxTokens" -> createMessage.maxTokens.asJson,
        "temperature" -> createMessage.temperature.asJson,
        "includeContext" -> createMessage.includeContext.asJson,
        "stopSequences" -> createMessage.stopSequences.asJson,
        "metadata" -> createMessage.metadata.asJson,
      ).deepMerge(
        createMessage._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
      )
    }
    given Decoder[CreateMessage] = Decoder.instance { c =>
      for
        messages <- c.downField("messages").as[List[Message]]
        modelPreferences <- c.downField("modelPreferences").as[Option[ModelPreferences]]
        systemPrompt <- c.downField("systemPrompt").as[Option[String]]
        maxTokens <- c.downField("maxTokens").as[Int]
        temperature <- c.downField("temperature").as[Option[Double]]
        includeContext <- c.downField("includeContext").as[Option[String]]
        stopSequences <- c.downField("stopSequences").as[Option[List[String]]]
        metadata <- c.downField("metadata").as[Option[JsonObject]]
        _meta <- c.downField("_meta").as[Option[JsonObject]]
      yield CreateMessage(
        messages,
        modelPreferences,
        systemPrompt,
        maxTokens,
        temperature,
        includeContext,
        stopSequences,
        metadata,
        _meta,
      )
    }

    case class Response(
      role: Role,
      content: Content,
      model: String,
      stopReason: Option[StopReason],
      _meta: Option[JsonObject] = None,
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "role" -> response.role.asJson,
          "content" -> response.content.asJson,
          "model" -> response.model.asJson,
          "stopReason" -> response.stopReason.asJson,
        ).deepMerge(
          response._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        for
          role <- c.downField("role").as[Role]
          content <- c.downField("content").as[Content]
          model <- c.downField("model").as[String]
          stopReason <- c.downField("stopReason").as[Option[StopReason]]
          _meta <- c.downField("_meta").as[Option[JsonObject]]
        yield Response(role, content, model, stopReason, _meta)
      }

  case class Message(role: Role, content: Content)

  object Message:
    given Encoder.AsObject[Message] = Encoder.AsObject.instance { message =>
      JsonObject(
        "role" -> message.role.asJson,
        "content" -> message.content.asJson,
      )
    }
    given Decoder[Message] = Decoder.instance { c =>
      for
        role <- c.downField("role").as[Role]
        content <- c.downField("content").as[Content]
      yield Message(role, content)
    }

  case class ModelPreferences(
    hints: Option[List[ModelHint]],
    costPriority: Option[Double],
    intelligencePriority: Option[Double],
    speedPriority: Option[Double],
  )

  object ModelPreferences:
    given Encoder.AsObject[ModelPreferences] = Encoder.AsObject.instance { prefs =>
      JsonObject(
        "hints" -> prefs.hints.asJson,
        "costPriority" -> prefs.costPriority.asJson,
        "intelligencePriority" -> prefs.intelligencePriority.asJson,
        "speedPriority" -> prefs.speedPriority.asJson,
      )
    }
    given Decoder[ModelPreferences] = Decoder.instance { c =>
      for
        hints <- c.downField("hints").as[Option[List[ModelHint]]]
        costPriority <- c.downField("costPriority").as[Option[Double]]
        intelligencePriority <- c.downField("intelligencePriority").as[Option[Double]]
        speedPriority <- c.downField("speedPriority").as[Option[Double]]
      yield ModelPreferences(hints, costPriority, intelligencePriority, speedPriority)
    }

  case class ModelHint(name: Option[String])

  object ModelHint:
    given Encoder.AsObject[ModelHint] = Encoder.AsObject.instance { hint =>
      JsonObject(
        "name" -> hint.name.asJson
      )
    }
    given Decoder[ModelHint] = Decoder.instance { c =>
      c.downField("name").as[Option[String]].map(ModelHint.apply)
    }

  enum StopReason:
    case EndTurn
    case StopSequence
    case MaxTokens
    case Other(reason: String)

  object StopReason:
    def fromString(s: String): StopReason = s match
      case "endTurn"      => EndTurn
      case "stopSequence" => StopSequence
      case "maxTokens"    => MaxTokens
      case other          => Other(other)

    given Encoder[StopReason] = Encoder.instance {
      case EndTurn       => "endTurn".asJson
      case StopSequence  => "stopSequence".asJson
      case MaxTokens     => "maxTokens".asJson
      case Other(reason) => reason.asJson
    }

    given Decoder[StopReason] = Decoder.instance { c =>
      c.as[String].map(fromString)
    }
