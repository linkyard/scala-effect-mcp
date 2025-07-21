package ch.linkyard.mcp.protocol

import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

case class Cancelled(
  requestId: RequestId,
  reason: String,
  _meta: Meta = Meta.empty,
) extends Notification:
  override val method: NotificationMethod = NotificationMethod.Cancelled

object Cancelled:
  given Encoder.AsObject[Cancelled] = Encoder.AsObject.instance { cancelled =>
    JsonObject(
      "requestId" -> cancelled.requestId.asJson,
      "reason" -> cancelled.reason.asJson,
      "_meta" -> cancelled._meta.asJson,
    )
  }
  given Decoder[Cancelled] = Decoder.instance { c =>
    for
      requestId <- c.downField("requestId").as[RequestId]
      reason <- c.downField("reason").as[String]
      _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
    yield Cancelled(requestId, reason, _meta)
  }

case class Ping(
  _meta: Meta = Meta.empty
) extends Request:
  override val method: RequestMethod = RequestMethod.Ping
  override type Response = Ping.Response

object Ping:
  given Encoder.AsObject[Ping] = Encoder.AsObject.instance { ping =>
    JsonObject(
      "_meta" -> ping._meta.asJson
    )
  }
  given Decoder[Ping] = Decoder.instance { c =>
    c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty)).map(Ping.apply)
  }

  case class Response(
    _meta: Meta = Meta.empty
  ) extends McpResponse

  object Response:
    given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
      JsonObject(
        "_meta" -> response._meta.asJson
      )
    }
    given Decoder[Response] = Decoder.instance { c =>
      c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty)).map(Response.apply)
    }

case class ProgressNotification(
  progressToken: ProgressToken,
  progress: Double,
  total: Option[Double],
  message: Option[String],
  _meta: Meta = Meta.empty,
) extends Notification:
  override val method: NotificationMethod = NotificationMethod.Progress

object ProgressNotification:
  given Encoder.AsObject[ProgressNotification] = Encoder.AsObject.instance { notification =>
    JsonObject(
      "progressToken" -> notification.progressToken.asJson,
      "progress" -> notification.progress.asJson,
      "total" -> notification.total.asJson,
      "message" -> notification.message.asJson,
      "_meta" -> notification._meta.asJson,
    )
  }
  given Decoder[ProgressNotification] = Decoder.instance { c =>
    for
      progressToken <- c.downField("progressToken").as[ProgressToken]
      progress <- c.downField("progress").as[Double]
      total <- c.downField("total").as[Option[Double]]
      message <- c.downField("message").as[Option[String]]
      _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
    yield ProgressNotification(progressToken, progress, total, message, _meta)
  }

object Logging:
  case class SetLevel(
    level: LoggingLevel,
    _meta: Meta = Meta.empty,
  ) extends Request:
    override type Response = SetLevel.Response
    override val method: RequestMethod = RequestMethod.SetLevel

  object SetLevel:
    given Encoder.AsObject[SetLevel] = Encoder.AsObject.instance { setLevel =>
      JsonObject(
        "level" -> setLevel.level.asJson,
        "_meta" -> setLevel._meta.asJson,
      )
    }
    given Decoder[SetLevel] = Decoder.instance { c =>
      for
        level <- c.downField("level").as[LoggingLevel]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield SetLevel(level, _meta)
    }

    case class Response(
      _meta: Meta = Meta.empty
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "_meta" -> response._meta.asJson
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty)).map(Response.apply)
      }

  case class LoggingMessage(
    level: LoggingLevel,
    logger: Option[String],
    data: io.circe.Json,
    _meta: Meta = Meta.empty,
  ) extends Notification:
    override val method: NotificationMethod = NotificationMethod.LoggingMessage

  object LoggingMessage:
    given Encoder.AsObject[LoggingMessage] = Encoder.AsObject.instance { message =>
      JsonObject(
        "level" -> message.level.asJson,
        "logger" -> message.logger.asJson,
        "data" -> message.data.asJson,
        "_meta" -> message._meta.asJson,
      )
    }
    given Decoder[LoggingMessage] = Decoder.instance { c =>
      for
        level <- c.downField("level").as[LoggingLevel]
        logger <- c.downField("logger").as[Option[String]]
        data <- c.downField("data").as[io.circe.Json]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield LoggingMessage(level, logger, data, _meta)
    }

enum CompletionReference:
  case PromptReference(name: String, title: Option[String])
  case ResourceTemplateReference(uri: String)

object CompletionReference:
  given Encoder[CompletionReference] = Encoder.instance {
    case PromptReference(name, title) =>
      JsonObject(
        "name" -> name.asJson,
        "title" -> title.asJson,
      ).asJson
    case ResourceTemplateReference(uri) =>
      JsonObject(
        "uri" -> uri.asJson
      ).asJson
  }
  given Decoder[CompletionReference] = Decoder.instance { c =>
    c.downField("name").as[String].map { name =>
      c.downField("title").as[Option[String]].map(title => PromptReference(name, title))
    }.getOrElse {
      c.downField("uri").as[String].map(ResourceTemplateReference.apply)
    }
  }

case class Completion(
  values: List[String],
  total: Option[Int] = None,
  hasMore: Option[Boolean] = None,
)
object Completion:
  case class Complete(
    ref: CompletionReference,
    argument: Complete.Argument,
    context: Option[Complete.Context],
    _meta: Meta = Meta.empty,
  ) extends Request:
    override type Response = Complete.Response
    override val method: RequestMethod = RequestMethod.Complete

  object Complete:
    given Encoder.AsObject[Complete] = Encoder.AsObject.instance { complete =>
      JsonObject(
        "ref" -> complete.ref.asJson,
        "argument" -> complete.argument.asJson,
        "context" -> complete.context.asJson,
        "_meta" -> complete._meta.asJson,
      )
    }
    given Decoder[Complete] = Decoder.instance { c =>
      for
        ref <- c.downField("ref").as[CompletionReference]
        argument <- c.downField("argument").as[Argument]
        context <- c.downField("context").as[Option[Context]]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Complete(ref, argument, context, _meta)
    }

    case class Argument(name: String, value: String)
    object Argument:
      given Encoder.AsObject[Argument] = Encoder.AsObject.instance { arg =>
        JsonObject(
          "name" -> arg.name.asJson,
          "value" -> arg.value.asJson,
        )
      }
      given Decoder[Argument] = Decoder.instance { c =>
        for
          name <- c.downField("name").as[String]
          value <- c.downField("value").as[String]
        yield Argument(name, value)
      }

    case class Context(arguments: Option[Map[String, String]])
    object Context:
      given Encoder.AsObject[Context] = Encoder.AsObject.instance { ctx =>
        JsonObject(
          "arguments" -> ctx.arguments.asJson
        )
      }
      given Decoder[Context] = Decoder.instance { c =>
        c.downField("arguments").as[Option[Map[String, String]]].map(Context.apply)
      }

    case class Response(
      completion: Completion,
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "completion" -> response.completion.asJson,
          "_meta" -> response._meta.asJson,
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        for
          completion <- c.downField("completion").as[Completion]
          _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
        yield Response(completion, _meta)
      }

  given Encoder.AsObject[Completion] = Encoder.AsObject.instance { completion =>
    JsonObject(
      "values" -> completion.values.asJson,
      "total" -> completion.total.asJson,
      "hasMore" -> completion.hasMore.asJson,
    )
  }
  given Decoder[Completion] = Decoder.instance { c =>
    for
      values <- c.downField("values").as[List[String]]
      total <- c.downField("total").as[Option[Int]]
      hasMore <- c.downField("hasMore").as[Option[Boolean]]
    yield Completion(values, total, hasMore)
  }
