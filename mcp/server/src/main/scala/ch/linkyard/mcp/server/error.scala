package ch.linkyard.mcp.server

import cats.MonadThrow
import cats.effect.kernel.Async
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import io.circe.Json
import io.circe.syntax.*

case class McpError(
  errorCode: ErrorCode,
  message: String,
  data: Option[Json],
  /** The error for a resource that does not exist (the code depends on the protocol version) */
  isResourceNotFound: Boolean = false,
)

object McpError:
  def error(errorCode: ErrorCode, message: String, data: Option[Json] = None): McpErrorException =
    McpErrorException(McpError(errorCode, message, data))
  def raise[F[_]: MonadThrow](errorCode: ErrorCode, message: String, data: Option[Json] = None): F[Nothing] =
    MonadThrow[F].raiseError(McpErrorException(McpError(errorCode, message, data)))

  /** The resource does not exist */
  def resourceNotFound(uri: String): McpErrorException =
    McpErrorException(McpError(
      ErrorCode.InvalidParams,
      s"Resource not found: $uri",
      Some(Json.obj("uri" -> uri.asJson)),
      isResourceNotFound = true,
    ))
  def raiseResourceNotFound[F[_]: MonadThrow](uri: String): F[Nothing] =
    MonadThrow[F].raiseError(resourceNotFound(uri))

  case class McpErrorException(error: McpError) extends RuntimeException(error.message)

extension [A](a: Either[McpError, A])
  def liftTo[F[_]: Async]: F[A] = a match
    case Right(a)    => Async[F].pure(a)
    case Left(error) => Async[F].raiseError(McpError.McpErrorException(error))
