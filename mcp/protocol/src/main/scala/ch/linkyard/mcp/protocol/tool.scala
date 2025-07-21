package ch.linkyard.mcp.protocol

import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

case class Tool(
  name: String,
  title: Option[String],
  description: Option[String],
  inputSchema: JsonSchema,
  outputSchema: Option[JsonSchema],
  annotations: Option[Tool.Annotations],
  _meta: Meta = Meta.empty,
)

object Tool:
  given Encoder.AsObject[Tool] = Encoder.AsObject.instance { tool =>
    JsonObject(
      "name" -> tool.name.asJson,
      "title" -> tool.title.asJson,
      "description" -> tool.description.asJson,
      "inputSchema" -> tool.inputSchema.asJson,
      "outputSchema" -> tool.outputSchema.asJson,
      "annotations" -> tool.annotations.asJson,
      "_meta" -> tool._meta.asJson,
    )
  }
  given Decoder[Tool] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      title <- c.downField("title").as[Option[String]]
      description <- c.downField("description").as[Option[String]]
      inputSchema <- c.downField("inputSchema").as[JsonSchema]
      outputSchema <- c.downField("outputSchema").as[Option[JsonSchema]]
      annotations <- c.downField("annotations").as[Option[Annotations]]
      _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
    yield Tool(name, title, description, inputSchema, outputSchema, annotations, _meta)
  }

  case class ListTools(
    cursor: Option[Cursor],
    _meta: Meta = Meta.empty,
  ) extends Request:
    override type Response = ListTools.Response
    override val method: RequestMethod = RequestMethod.ListTools

  object ListTools:
    given Encoder.AsObject[ListTools] = Encoder.AsObject.instance { listTools =>
      JsonObject(
        "cursor" -> listTools.cursor.asJson,
        "_meta" -> listTools._meta.asJson,
      )
    }
    given Decoder[ListTools] = Decoder.instance { c =>
      for
        cursor <- c.downField("cursor").as[Option[Cursor]]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield ListTools(cursor, _meta)
    }

    case class Response(
      tools: List[Tool],
      nextCursor: Option[Cursor],
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "tools" -> response.tools.asJson,
          "nextCursor" -> response.nextCursor.asJson,
          "_meta" -> response._meta.asJson,
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        for
          tools <- c.downField("tools").as[List[Tool]]
          nextCursor <- c.downField("nextCursor").as[Option[Cursor]]
          _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
        yield Response(tools, nextCursor, _meta)
      }

  case class CallTool(
    name: String,
    arguments: JsonObject,
    _meta: Meta = Meta.empty,
  ) extends Request:
    override type Response = CallTool.Response
    override val method: RequestMethod = RequestMethod.CallTool

  object CallTool:
    given Encoder.AsObject[CallTool] = Encoder.AsObject.instance { callTool =>
      JsonObject(
        "name" -> callTool.name.asJson,
        "arguments" -> callTool.arguments.asJson,
        "_meta" -> callTool._meta.asJson,
      )
    }
    given Decoder[CallTool] = Decoder.instance { c =>
      for
        name <- c.downField("name").as[String]
        arguments <- c.downField("arguments").as[JsonObject]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield CallTool(name, arguments, _meta)
    }

    enum Response extends McpResponse:
      case Success(
        content: List[Content],
        structuredContent: Option[JsonObject],
        _meta: Meta = Meta.empty,
      )
      case Error(
        content: List[Content],
        _meta: Meta = Meta.empty,
      )

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance {
        case Success(content, structuredContent, _meta) => JsonObject(
            "content" -> content.asJson,
            "structuredContent" -> structuredContent.asJson,
            "_meta" -> _meta.asJson,
          )
        case Error(content, _meta) => JsonObject(
            "content" -> content.asJson,
            "isError" -> true.asJson,
            "_meta" -> _meta.asJson,
          )
      }

      given Decoder[Response] = Decoder.instance { c =>
        for
          isError <- c.downField("isError").as[Option[Boolean]]
          structuredContent <- c.downField("structuredContent").as[Option[JsonObject]]
          content <- c.downField("content").as[List[Content]]
          _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
        yield {
          if isError.contains(true) then Error(content, _meta)
          else Success(content, structuredContent, _meta)
        }
      }

  case class ListChanged(
    _meta: Meta = Meta.empty
  ) extends Notification:
    override val method: NotificationMethod = NotificationMethod.ToolListChanged

  object ListChanged:
    given Encoder.AsObject[ListChanged] = Encoder.AsObject.instance { listChanged =>
      JsonObject(
        "_meta" -> listChanged._meta.asJson
      )
    }
    given Decoder[ListChanged] = Decoder.instance { c =>
      c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty)).map(ListChanged.apply)
    }

  case class Annotations(
    title: Option[String],
    readOnlyHint: Option[Boolean],
    destructiveHint: Option[Boolean],
    idempotentHint: Option[Boolean],
    openWorldHint: Option[Boolean],
  )

  object Annotations:
    given Encoder.AsObject[Annotations] = Encoder.AsObject.instance { annotations =>
      JsonObject(
        "title" -> annotations.title.asJson,
        "readOnlyHint" -> annotations.readOnlyHint.asJson,
        "destructiveHint" -> annotations.destructiveHint.asJson,
        "idempotentHint" -> annotations.idempotentHint.asJson,
        "openWorldHint" -> annotations.openWorldHint.asJson,
      )
    }
    given Decoder[Annotations] = Decoder.instance { c =>
      for
        title <- c.downField("title").as[Option[String]]
        readOnlyHint <- c.downField("readOnlyHint").as[Option[Boolean]]
        destructiveHint <- c.downField("destructiveHint").as[Option[Boolean]]
        idempotentHint <- c.downField("idempotentHint").as[Option[Boolean]]
        openWorldHint <- c.downField("openWorldHint").as[Option[Boolean]]
      yield Annotations(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint)
    }
