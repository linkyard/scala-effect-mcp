package ch.linkyard.mcp.protocol

import io.circe.Codec
import io.circe.derivation.ConfiguredCodec

/** Which notifications a client wants to receive on a `subscriptions/listen` stream. */
case class SubscriptionFilter(
  toolsListChanged: Option[Boolean] = None,
  promptsListChanged: Option[Boolean] = None,
  resourcesListChanged: Option[Boolean] = None,
  resourceSubscriptions: Option[List[String]] = None,
)

object SubscriptionFilter:
  given Codec.AsObject[SubscriptionFilter] = ConfiguredCodec.derived[SubscriptionFilter].withoutNulls

object Subscriptions:
  case class Listen(
    notifications: SubscriptionFilter,
    _meta: Meta = Meta.empty,
  )

  object Listen:
    given Codec.AsObject[Listen] = ConfiguredCodec.derived[Listen].withoutNulls

    /** Sent when the server ends the subscription (graceful close). */
    case class Response(
      _meta: Meta = Meta.empty
    ) extends McpResponse

    object Response:
      given Codec.AsObject[Response] = completeResultCodec(ConfiguredCodec.derived[Response])

  /** First message on a subscription, the notifications that the server will send. */
  case class Acknowledged(
    notifications: SubscriptionFilter,
    _meta: Meta = Meta.empty,
  )

  object Acknowledged:
    given Codec.AsObject[Acknowledged] = ConfiguredCodec.derived[Acknowledged].withoutNulls
