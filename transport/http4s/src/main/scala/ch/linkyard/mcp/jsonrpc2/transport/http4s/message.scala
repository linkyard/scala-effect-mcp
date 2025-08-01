package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import cats.effect.SyncIO
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import io.circe.Json
import io.circe.syntax.*
import org.http4s.Request
import org.http4s.ServerSentEvent
import org.typelevel.vault.Key

extension (o: Json)
  private def relatesTo: Option[JsonRpc.Id] =
    o.hcursor.downField("_meta").downField("relatesTo").as[JsonRpc.Id].toOption

extension (msg: JsonRpc.Message)
  private def toSse = ServerSentEvent(data = msg.asJson.noSpaces.some)
  private def relatesTo: Option[JsonRpc.Id] = msg match
    case JsonRpc.Request(_, _, params)        => params.flatMap(_.toJson.relatesTo)
    case JsonRpc.Response.Success(id, _)      => id.some
    case JsonRpc.Response.Error(id, _, _, _)  => id.some
    case JsonRpc.Notification(method, params) => params.flatMap(_.toJson.relatesTo)

private val AuthenticationTokenAttribute = Key.newKey[SyncIO, String].unsafeRunSync()

extension (req: Request[IO])
  def authentication: Authentication = req.attributes.lookup(AuthenticationTokenAttribute) match
    case Some(token) if token.trim.nonEmpty => Authentication.BearerToken(token)
    case _                                  => Authentication.Anonymous
