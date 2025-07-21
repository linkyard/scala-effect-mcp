package ch.linkyard.mcp.protocol

import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

case class Initialize(
  capabilities: Initialize.ClientCapabilities,
  clientInfo: Initialize.PartyInfo,
  protocolVersion: String = Initialize.defaultProtocolVersion,
  _meta: Meta = Meta.empty,
) extends Request:
  override type Response = Initialize.Response
  override val method: RequestMethod = RequestMethod.Initialize

object Initialize:
  val defaultProtocolVersion: String = "2025-06-18"

  given Encoder.AsObject[Initialize] = Encoder.AsObject.instance { initialize =>
    JsonObject(
      "protocolVersion" -> initialize.protocolVersion.asJson,
      "capabilities" -> initialize.capabilities.asJson,
      "clientInfo" -> initialize.clientInfo.asJson,
      "_meta" -> initialize._meta.asJson,
    )
  }
  given Decoder[Initialize] = Decoder.instance { c =>
    for
      protocolVersion <- c.downField("protocolVersion").as[String]
      capabilities <- c.downField("capabilities").as[ClientCapabilities]
      clientInfo <- c.downField("clientInfo").as[PartyInfo]
      _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
    yield Initialize(capabilities, clientInfo, protocolVersion, _meta)
  }

  case class Response(
    serverInfo: PartyInfo,
    capabilities: ServerCapabilities,
    instructions: Option[String],
    protocolVersion: String = Initialize.defaultProtocolVersion,
    _meta: Meta = Meta.empty,
  ) extends McpResponse
  object Response:
    given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
      JsonObject(
        "protocolVersion" -> response.protocolVersion.asJson,
        "serverInfo" -> response.serverInfo.asJson,
        "capabilities" -> response.capabilities.asJson,
        "instructions" -> response.instructions.asJson,
        "_meta" -> response._meta.asJson,
      )
    }
    given Decoder[Response] = Decoder.instance { c =>
      for
        protocolVersion <- c.downField("protocolVersion").as[String]
        serverInfo <- c.downField("serverInfo").as[PartyInfo]
        capabilities <- c.downField("capabilities").as[ServerCapabilities]
        instructions <- c.downField("instructions").as[Option[String]]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Response(serverInfo, capabilities, instructions, protocolVersion, _meta)
    }

  case class PartyInfo(
    name: String,
    version: String,
  )
  object PartyInfo:
    given Encoder.AsObject[PartyInfo] = Encoder.AsObject.instance { info =>
      JsonObject(
        "name" -> info.name.asJson,
        "version" -> info.version.asJson,
      )
    }
    given Decoder[PartyInfo] = Decoder.instance { c =>
      for
        name <- c.downField("name").as[String]
        version <- c.downField("version").as[String]
      yield PartyInfo(name, version)
    }

  case class ClientCapabilities(
    roots: Option[Capabilities.Changable],
    sampling: Option[Capabilities.Supported],
    elicitation: Option[Capabilities.Supported],
    experimental: Option[JsonObject],
  )
  object ClientCapabilities:
    given Encoder.AsObject[ClientCapabilities] = Encoder.AsObject.instance { caps =>
      JsonObject(
        "roots" -> caps.roots.asJson,
        "sampling" -> caps.sampling.asJson,
        "elicitation" -> caps.elicitation.asJson,
        "experimental" -> caps.experimental.asJson,
      )
    }

    given Decoder[ClientCapabilities] = Decoder.instance { c =>
      for
        roots <- c.downField("roots").as[Option[Capabilities.Changable]]
        sampling <- c.downField("sampling").as[Option[Capabilities.Supported]]
        elicitation <- c.downField("elicitation").as[Option[Capabilities.Supported]]
        experimental <- c.downField("experimental").as[Option[JsonObject]]
      yield ClientCapabilities(roots, sampling, elicitation, experimental)
    }

  case class ServerCapabilities(
    prompts: Option[Capabilities.Changable],
    resources: Option[Capabilities.Subscribable],
    tools: Option[Capabilities.Changable],
    logging: Option[Capabilities.Supported],
    completions: Option[Capabilities.Supported],
    experimental: Option[JsonObject],
  )
  object ServerCapabilities:
    given Encoder.AsObject[ServerCapabilities] = Encoder.AsObject.instance { caps =>
      JsonObject(
        "prompts" -> caps.prompts.asJson,
        "resources" -> caps.resources.asJson,
        "tools" -> caps.tools.asJson,
        "logging" -> caps.logging.asJson,
        "completions" -> caps.completions.asJson,
        "experimental" -> caps.experimental.asJson,
      )
    }

    given Decoder[ServerCapabilities] = Decoder.instance { c =>
      for
        prompts <- c.downField("prompts").as[Option[Capabilities.Changable]]
        resources <- c.downField("resources").as[Option[Capabilities.Subscribable]]
        tools <- c.downField("tools").as[Option[Capabilities.Changable]]
        logging <- c.downField("logging").as[Option[Capabilities.Supported]]
        completions <- c.downField("completions").as[Option[Capabilities.Supported]]
        experimental <- c.downField("experimental").as[Option[JsonObject]]
      yield ServerCapabilities(prompts, resources, tools, logging, completions, experimental)
    }

  object Capabilities:
    case class Changable(listChanged: Boolean)
    object Changable:
      given Encoder.AsObject[Changable] = Encoder.AsObject.instance { changable =>
        JsonObject("listChanged" -> changable.listChanged.asJson)
      }

      given Decoder[Changable] = Decoder.instance { c =>
        c.downField("listChanged").as[Boolean].map(Changable.apply)
      }

    case class Subscribable(subscribe: Boolean, listChanged: Boolean)
    object Subscribable:
      given Encoder.AsObject[Subscribable] = Encoder.AsObject.instance { subscribable =>
        JsonObject(
          "subscribe" -> subscribable.subscribe.asJson,
          "listChanged" -> subscribable.listChanged.asJson,
        )
      }

      given Decoder[Subscribable] = Decoder.instance { c =>
        for
          subscribe <- c.downField("subscribe").as[Boolean]
          listChanged <- c.downField("listChanged").as[Boolean]
        yield Subscribable(subscribe, listChanged)
      }

    case class Supported()
    object Supported:
      given Encoder.AsObject[Supported] = Encoder.AsObject.instance { _ =>
        JsonObject.empty
      }

      given Decoder[Supported] = Decoder.instance { _ =>
        Right(Supported())
      }

case class Initialized(
  _meta: Meta = Meta.empty
) extends Notification:
  override val method: NotificationMethod = NotificationMethod.Initialized

object Initialized:
  given Encoder.AsObject[Initialized] = Encoder.AsObject.instance { initialized =>
    JsonObject(
      "_meta" -> initialized._meta.asJson
    )
  }
  given Decoder[Initialized] = Decoder.instance { c =>
    c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty)).map(Initialized.apply)
  }
