package ch.linkyard.mcp.server

import ch.linkyard.mcp.protocol.LoggingLevel
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

trait CallContext[F[_]]:
  def meta: Option[JsonObject]
  def reportProgress(progress: Double, total: Option[Double] = None, message: Option[String] = None): F[Unit]
  def log(level: LoggingLevel, message: String): F[Unit] = log(level, message.asJson)
  def log(level: LoggingLevel, data: Json): F[Unit]
