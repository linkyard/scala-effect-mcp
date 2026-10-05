package ch.linkyard.mcp.server

import ch.linkyard.mcp.jsonrpc2.Authentication

import java.security.MessageDigest
import java.security.SecureRandom
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/** How the state that is passed through the client is protected (see [[Outcome.InputRequired]]). */
case class RequestStateConfig(
  /** The first key protects new states, all keys are accepted when verifying (allows rotating keys). All instances of a
    * server have to use the same keys.
    */
  keys: List[Array[Byte]],
  /** For how long a state is valid */
  timeToLive: FiniteDuration = 10.minutes,
  /** Identifies the user, a state is only accepted from the user it was created for. The default is the hash of the
    * bearer token, which means states are not valid anymore after the token was refreshed.
    */
  principal: Authentication => String = RequestStateConfig.defaultPrincipal,
):
  require(keys.nonEmpty, "at least one key is required")
  require(keys.forall(_.length >= 16), "the keys need at least 16 bytes")

object RequestStateConfig:
  /** A random key, only valid in this process (not for servers that run in several instances). */
  def random(timeToLive: FiniteDuration = 10.minutes): RequestStateConfig =
    val key = new Array[Byte](32)
    SecureRandom().nextBytes(key)
    RequestStateConfig(List(key), timeToLive)

  def defaultPrincipal(authentication: Authentication): String = authentication match
    case Authentication.Anonymous          => "anonymous"
    case Authentication.BearerToken(token) =>
      "bearer:" + MessageDigest.getInstance("SHA-256").digest(token.getBytes("UTF-8")).map("%02x".format(_)).mkString

  /** One random key per process. */
  val default: RequestStateConfig = random()

case class McpServerConfig(
  requestState: RequestStateConfig = RequestStateConfig.default,
  /** Also serve the clients of the earlier protocol versions (2025-06-18 and 2025-11-25) */
  supportLegacyClients: Boolean = true,
  /** How often a request that needs input is retried for a client of an earlier protocol version. */
  maxLegacyInputRounds: Int = 10,
)

object McpServerConfig:
  val default: McpServerConfig = McpServerConfig()
