package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.kernel.Sync
import cats.effect.std.SecureRandom
import cats.implicits.*

opaque type SessionId = String
object SessionId:
  def generate[F[_]: Sync]: F[SessionId] =
    for
      random <- SecureRandom.javaSecuritySecureRandom[F]
      id <- (1 to 32).toList.traverse(_ => random.nextAlphaNumeric)
    yield id.mkString

  def parse(s: String): Option[SessionId] =
    Some(s).filter(_.length == 32)

  extension (id: SessionId)
    def asString: String = id
