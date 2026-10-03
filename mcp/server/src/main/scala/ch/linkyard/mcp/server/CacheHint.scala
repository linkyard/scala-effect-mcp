package ch.linkyard.mcp.server

import ch.linkyard.mcp.protocol.CacheScope

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

/** How long a client may keep a result and who may cache it (see the caching section of the specification). */
case class CacheHint(ttl: FiniteDuration, scope: CacheScope):
  def ttlMs: Long = ttl.toMillis

object CacheHint:
  /** The result is stale immediately. */
  val none: CacheHint = CacheHint(Duration.Zero, CacheScope.Private)

  /** Same for all users, may be cached by shared caches. */
  def public(ttl: FiniteDuration): CacheHint = CacheHint(ttl, CacheScope.Public)

  /** May only be reused for the same authorization. */
  def perAuthorization(ttl: FiniteDuration): CacheHint = CacheHint(ttl, CacheScope.Private)
