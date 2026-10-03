package ch.linkyard.mcp.jsonrpc2.transport.http4s

import org.http4s.headers.Origin

import scala.concurrent.duration.*

/** Configuration of the [[McpServerRoute]].
  *
  * @param originAllowed
  *   decides about requests with an `Origin` header, requests with a rejected origin are answered with 403. Requests
  *   without `Origin` header (clients that are not browsers) are always accepted. The host of the request does not
  *   matter, because the origin of a DNS rebinding attack has the same host as the request. By default only `localhost`
  *   and its loopback addresses are accepted, a server that is used by browsers has to allow their origins.
  * @param keepAliveInterval
  *   interval of the SSE comments that keep open streams alive
  */
case class McpServerRouteConfig(
  originAllowed: Origin => Boolean = McpServerRouteConfig.localOrigin,
  keepAliveInterval: FiniteDuration = 30.seconds,
)

object McpServerRouteConfig:
  private val localHosts = Set("localhost", "127.0.0.1", "[::1]")

  /** Accepts the origins on localhost, 127.0.0.1 and [::1] (any scheme and port). */
  val localOrigin: Origin => Boolean =
    case Origin.HostList(hosts) => hosts.forall(h => localHosts.contains(h.host.renderString.toLowerCase))
    case _                      => false

  val default: McpServerRouteConfig = McpServerRouteConfig()
