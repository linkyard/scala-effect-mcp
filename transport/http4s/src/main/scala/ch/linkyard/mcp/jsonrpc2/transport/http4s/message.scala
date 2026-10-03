package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import cats.effect.SyncIO
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import io.circe.syntax.*
import org.http4s.Request
import org.http4s.ServerSentEvent
import org.typelevel.vault.Key

extension (msg: JsonRpc.Message)
  private def toSse = ServerSentEvent(data = msg.asJson.noSpaces.some)

private val AuthenticationTokenAttribute = Key.newKey[SyncIO, String].unsafeRunSync()

extension (req: Request[IO])
  def authentication: Authentication = req.attributes.lookup(AuthenticationTokenAttribute) match
    case Some(token) if token.trim.nonEmpty => Authentication.BearerToken(token)
    case _                                  => Authentication.Anonymous
