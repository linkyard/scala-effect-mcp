package ch.linkyard.mcp.protocol

import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

case class Prompt(
  name: String,
  title: Option[String],
  description: Option[String],
  arguments: Option[List[PromptArgument]],
  _meta: Option[JsonObject] = None,
)

object Prompt:
  given Encoder.AsObject[Prompt] = Encoder.AsObject.instance { prompt =>
    JsonObject(
      "name" -> prompt.name.asJson,
      "title" -> prompt.title.asJson,
      "description" -> prompt.description.asJson,
      "arguments" -> prompt.arguments.asJson,
    ).deepMerge(
      prompt._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
    )
  }
  given Decoder[Prompt] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      title <- c.downField("title").as[Option[String]]
      description <- c.downField("description").as[Option[String]]
      arguments <- c.downField("arguments").as[Option[List[PromptArgument]]]
      _meta <- c.downField("_meta").as[Option[JsonObject]]
    yield Prompt(name, title, description, arguments, _meta)
  }

case class PromptArgument(
  name: String,
  title: Option[String],
  description: Option[String],
  required: Option[Boolean],
)

object PromptArgument:
  given Encoder.AsObject[PromptArgument] = Encoder.AsObject.instance { arg =>
    JsonObject(
      "name" -> arg.name.asJson,
      "title" -> arg.title.asJson,
      "description" -> arg.description.asJson,
      "required" -> arg.required.asJson,
    )
  }
  given Decoder[PromptArgument] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      title <- c.downField("title").as[Option[String]]
      description <- c.downField("description").as[Option[String]]
      required <- c.downField("required").as[Option[Boolean]]
    yield PromptArgument(name, title, description, required)
  }

case class PromptMessage(
  role: Role,
  content: Content,
)

object PromptMessage:
  given Encoder.AsObject[PromptMessage] = Encoder.AsObject.instance { message =>
    JsonObject(
      "role" -> message.role.asJson,
      "content" -> message.content.asJson,
    )
  }
  given Decoder[PromptMessage] = Decoder.instance { c =>
    for
      role <- c.downField("role").as[Role]
      content <- c.downField("content").as[Content]
    yield PromptMessage(role, content)
  }

case class PromptReference(
  name: String,
  title: Option[String],
)

object PromptReference:
  given Encoder.AsObject[PromptReference] = Encoder.AsObject.instance { ref =>
    JsonObject(
      "name" -> ref.name.asJson,
      "title" -> ref.title.asJson,
    )
  }
  given Decoder[PromptReference] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      title <- c.downField("title").as[Option[String]]
    yield PromptReference(name, title)
  }

object Prompts:
  case class ListPrompts(
    cursor: Option[Cursor],
    _meta: Option[JsonObject] = None,
  ) extends Request:
    override type Response = ListPrompts.Response
    override val method: RequestMethod = RequestMethod.ListPrompts

  object ListPrompts:
    given Encoder.AsObject[ListPrompts] = Encoder.AsObject.instance { listPrompts =>
      JsonObject(
        "cursor" -> listPrompts.cursor.asJson
      ).deepMerge(
        listPrompts._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
      )
    }
    given Decoder[ListPrompts] = Decoder.instance { c =>
      for
        cursor <- c.downField("cursor").as[Option[Cursor]]
        _meta <- c.downField("_meta").as[Option[JsonObject]]
      yield ListPrompts(cursor, _meta)
    }

    case class Response(
      prompts: List[Prompt],
      nextCursor: Option[Cursor],
      _meta: Option[JsonObject] = None,
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "prompts" -> response.prompts.asJson,
          "nextCursor" -> response.nextCursor.asJson,
        ).deepMerge(
          response._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        for
          prompts <- c.downField("prompts").as[List[Prompt]]
          nextCursor <- c.downField("nextCursor").as[Option[Cursor]]
          _meta <- c.downField("_meta").as[Option[JsonObject]]
        yield Response(prompts, nextCursor, _meta)
      }

  case class GetPrompt(
    name: String,
    arguments: Option[Map[String, String]],
    _meta: Option[JsonObject] = None,
  ) extends Request:
    override type Response = GetPrompt.Response
    override val method: RequestMethod = RequestMethod.GetPrompt

  object GetPrompt:
    given Encoder.AsObject[GetPrompt] = Encoder.AsObject.instance { getPrompt =>
      JsonObject(
        "name" -> getPrompt.name.asJson,
        "arguments" -> getPrompt.arguments.asJson,
      ).deepMerge(
        getPrompt._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
      )
    }
    given Decoder[GetPrompt] = Decoder.instance { c =>
      for
        name <- c.downField("name").as[String]
        arguments <- c.downField("arguments").as[Option[Map[String, String]]]
        _meta <- c.downField("_meta").as[Option[JsonObject]]
      yield GetPrompt(name, arguments, _meta)
    }

    case class Response(
      description: Option[String],
      messages: List[PromptMessage],
      _meta: Option[JsonObject] = None,
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "description" -> response.description.asJson,
          "messages" -> response.messages.asJson,
        ).deepMerge(
          response._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        for
          description <- c.downField("description").as[Option[String]]
          messages <- c.downField("messages").as[List[PromptMessage]]
          _meta <- c.downField("_meta").as[Option[JsonObject]]
        yield Response(description, messages, _meta)
      }

  case class ListChanged(
    _meta: Option[JsonObject] = None
  ) extends Notification:
    override val method: NotificationMethod = NotificationMethod.PromptListChanged

  object ListChanged:
    given Encoder.AsObject[ListChanged] = Encoder.AsObject.instance { listChanged =>
      listChanged._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
    }
    given Decoder[ListChanged] = Decoder.instance { c =>
      c.downField("_meta").as[Option[JsonObject]].map(ListChanged.apply)
    }
