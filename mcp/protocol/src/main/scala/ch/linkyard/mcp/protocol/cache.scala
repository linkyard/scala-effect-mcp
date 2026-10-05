package ch.linkyard.mcp.protocol

import io.circe.Codec

/** Who is allowed to cache a result. */
enum CacheScope:
  case Public
  case Private

object CacheScope:
  given Codec[CacheScope] = stringEnumCodec("cache scope", "public" -> Public, "private" -> Private)
