package ch.linkyard.mcp.server

import cats.MonadThrow
import cats.effect.kernel.Async
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import io.circe.Json

case class McpError(
  errorCode: ErrorCode,
  message: String,
  data: Option[Json],
)

object McpError:
  def error(errorCode: ErrorCode, message: String, data: Option[Json] = None): McpErrorException =
    McpErrorException(McpError(errorCode, message, data))
  def raise[F[_]: MonadThrow](errorCode: ErrorCode, message: String, data: Option[Json] = None): F[Nothing] =
    MonadThrow[F].raiseError(McpErrorException(McpError(errorCode, message, data)))

  case class McpErrorException(error: McpError) extends RuntimeException(error.message)

extension [A](a: Either[McpError, A])
  def liftTo[F[_]: Async]: F[A] = a match
    case Right(a)    => Async[F].pure(a)
    case Left(error) => Async[F].raiseError(McpError.McpErrorException(error))

extension [F[_]: Async, A](fa: F[Either[McpError, A]])
  def liftToF: F[A] = fa.flatMap(_.liftTo)
