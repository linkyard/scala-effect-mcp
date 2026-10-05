package ch.linkyard.mcp.protocol.legacy

import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.given
import io.circe.Codec
import io.circe.derivation.ConfiguredCodec

/** The protocol versions with a handshake (`initialize`) that are still served for old clients. */
object LegacyVersion:
  val V2025_06_18 = "2025-06-18"
  val V2025_11_25 = "2025-11-25"

  /** Newest first */
  val all: List[String] = List(V2025_11_25, V2025_06_18)

  /** The version to use for a client that asks for `requested`: the same if we know it, else our newest. */
  def negotiate(requested: String): String = all.find(_ == requested).getOrElse(all.head)

/** The first request of a legacy client. */
case class Initialize(
  protocolVersion: String,
  capabilities: ClientCapabilities,
  clientInfo: Implementation,
  _meta: Meta = Meta.empty,
)
object Initialize:
  given Codec.AsObject[Initialize] = ConfiguredCodec.derived[Initialize].withoutNulls

case class InitializeResult(
  protocolVersion: String,
  capabilities: ServerCapabilities,
  serverInfo: Implementation,
  instructions: Option[String] = None,
  _meta: Meta = Meta.empty,
)
object InitializeResult:
  given Codec.AsObject[InitializeResult] = ConfiguredCodec.derived[InitializeResult].withoutNulls

case class Ping(_meta: Meta = Meta.empty)
object Ping:
  given Codec.AsObject[Ping] = ConfiguredCodec.derived[Ping].withoutNulls

/** `logging/setLevel`, logging is not supported: the level is read but not used. */
case class SetLevel(level: String, _meta: Meta = Meta.empty)
object SetLevel:
  given Codec.AsObject[SetLevel] = ConfiguredCodec.derived[SetLevel].withoutNulls

case class Subscribe(uri: String, _meta: Meta = Meta.empty)
object Subscribe:
  given Codec.AsObject[Subscribe] = ConfiguredCodec.derived[Subscribe].withoutNulls

case class Unsubscribe(uri: String, _meta: Meta = Meta.empty)
object Unsubscribe:
  given Codec.AsObject[Unsubscribe] = ConfiguredCodec.derived[Unsubscribe].withoutNulls

/** The requests of a legacy client: the ones that only exist in the earlier versions and those with the same shape as
  * in 2026-07-28 (without the per-request `_meta`). `server/discover` and `subscriptions/listen` are never decoded.
  */
type LegacyRequest = Initialize | Ping | SetLevel | Subscribe | Unsubscribe | ClientRequest

/** The notifications of a legacy client that matter. */
enum LegacyNotification:
  case Initialized
  case Cancelled(cancelled: ch.linkyard.mcp.protocol.Cancelled)

  /** Notifications that are ignored (roots changes, progress, ...) */
  case Ignored(method: String)
