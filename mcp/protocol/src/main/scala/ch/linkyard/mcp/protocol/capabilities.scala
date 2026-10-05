package ch.linkyard.mcp.protocol

import io.circe.Codec
import io.circe.JsonObject
import io.circe.derivation.ConfiguredCodec

/** What a client declares in the `_meta` of each request. Roots and sampling are not supported by this library, they
  * are only kept to round trip the messages.
  */
case class ClientCapabilities(
  experimental: Option[Map[String, JsonObject]] = None,
  roots: Option[JsonObject] = None,
  sampling: Option[JsonObject] = None,
  elicitation: Option[ClientCapabilities.Elicitation] = None,
  extensions: Option[Map[String, JsonObject]] = None,
):
  /** An empty elicitation capability means form mode. */
  def supportsFormElicitation: Boolean = elicitation.exists(e => e.form.isDefined || e.url.isEmpty)
  def supportsUrlElicitation: Boolean = elicitation.exists(_.url.isDefined)

object ClientCapabilities:
  val empty: ClientCapabilities = ClientCapabilities()

  case class Elicitation(form: Option[JsonObject] = None, url: Option[JsonObject] = None)
  object Elicitation:
    given Codec.AsObject[Elicitation] = ConfiguredCodec.derived[Elicitation].withoutNulls

  given Codec.AsObject[ClientCapabilities] = ConfiguredCodec.derived[ClientCapabilities].withoutNulls

case class ServerCapabilities(
  experimental: Option[Map[String, JsonObject]] = None,
  logging: Option[JsonObject] = None,
  completions: Option[JsonObject] = None,
  prompts: Option[ServerCapabilities.Prompts] = None,
  resources: Option[ServerCapabilities.Resources] = None,
  tools: Option[ServerCapabilities.Tools] = None,
  extensions: Option[Map[String, JsonObject]] = None,
)

object ServerCapabilities:
  case class Prompts(listChanged: Option[Boolean] = None)
  object Prompts:
    given Codec.AsObject[Prompts] = ConfiguredCodec.derived[Prompts].withoutNulls

  case class Resources(subscribe: Option[Boolean] = None, listChanged: Option[Boolean] = None)
  object Resources:
    given Codec.AsObject[Resources] = ConfiguredCodec.derived[Resources].withoutNulls

  case class Tools(listChanged: Option[Boolean] = None)
  object Tools:
    given Codec.AsObject[Tools] = ConfiguredCodec.derived[Tools].withoutNulls

  given Codec.AsObject[ServerCapabilities] = ConfiguredCodec.derived[ServerCapabilities].withoutNulls
