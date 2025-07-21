package ch.linkyard.mcp.server

import cats.effect.Concurrent
import cats.effect.Resource
import cats.effect.implicits.*
import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import cats.effect.std.Queue
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.jsonrpc2.JsonRpc.Message
import ch.linkyard.mcp.jsonrpc2.JsonRpcServer
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.ClientNotification
import ch.linkyard.mcp.protocol.ClientRequest
import ch.linkyard.mcp.protocol.Codec
import ch.linkyard.mcp.protocol.Codec.fromJsonRpc
import ch.linkyard.mcp.protocol.Codec.toJsonRpc
import ch.linkyard.mcp.protocol.ServerNotification
import ch.linkyard.mcp.protocol.ServerRequest
import fs2.Pipe
import io.circe.Decoder
import io.circe.DecodingFailure

import java.util.UUID

/** Lower level server that just deals with request/response correlation and does not care about the individual messages
  */
trait LowlevelMcpServer[F[_]]:
  def handleRequest(request: ClientRequest, id: RequestId): F[ServerResponse]
  def handleNotification(notification: ClientNotification): F[Unit]

object LowlevelMcpServer:
  /** The 'callback' interface for the servers (how it can send things to the client) */
  trait Communication[F[_]]:
    def request(request: ServerRequest)(using Decoder[request.Response]): F[Either[McpError, request.Response]]
    def notify(notification: ServerNotification): F[Unit]

  private type RequestResponseHandler[F[_]] = JsonRpc.Response => F[Unit]
  private case class State[F[_]: Concurrent](
    pendingRequests: Map[RequestId, RequestResponseHandler[F]] = Map.empty,
    pendingProcessings: Map[RequestId, Fiber[F, Throwable, Unit]] = Map.empty[RequestId, Fiber[F, Throwable, Unit]],
  ):
    def registerRequest(requestId: RequestId, handler: RequestResponseHandler[F]): State[F] =
      copy(pendingRequests = pendingRequests.updated(requestId, handler))
    def handleResponse(response: JsonRpc.Response): F[Unit] =
      pendingRequests.get(response.id.fromJsonRpc) match
        case Some(handler) => handler(response)
        case None          => Concurrent[F].unit // unknown request id, ignore

    def registerResponseProcessing(id: RequestId, fiber: Fiber[F, Throwable, Unit]): State[F] =
      copy(pendingProcessings = pendingProcessings.updated(id, fiber))
    def responseProcessingCompleted(id: RequestId): State[F] =
      copy(pendingProcessings = pendingProcessings.removed(id))
    def cancelResponseProcessing(id: RequestId): F[Unit] =
      pendingProcessings.get(id).traverse(_.cancel).void
  end State

  def start[F[_]: Async](
    createServer: Communication[F] => Resource[F, LowlevelMcpServer[F]],
    onError: DecodingFailure => F[Unit],
  ): Resource[F, JsonRpcServer[F]] =
    Resource.eval((Ref.of(State[F]()), Queue.unbounded[F, JsonRpc.Message]).tupled)
      .map((stateRef, outQueue) => (stateRef, outQueue, Comms[F](stateRef, outQueue)))
      .flatMap((stateRef, outQueue, comms) => createServer(comms).map((server) => (stateRef, outQueue, server)))
      .map((stateRef, outQueue, server) =>
        def processRequest(id: RequestId, request: ClientRequest): F[Unit] =
          for
            fiber <- server.handleRequest(request, id)
              .map(Codec.encodeResponse(id, _))
              .handleError {
                case McpError.McpErrorException(error) =>
                  JsonRpc.Response.Error(id.toJsonRpc, error.errorCode, error.message, error.data)
                case DecodingFailure(message, _) =>
                  JsonRpc.Response.Error(id.toJsonRpc, ErrorCode.ParseError, message, None)
                case error => JsonRpc.Response.Error(id.toJsonRpc, ErrorCode.InternalError, "Internal error", None)
              }.flatMap(outQueue.offer).void
              .guarantee(stateRef.update(s => s.responseProcessingCompleted(id)))
              .start
            _ <- stateRef.update(s => s.registerResponseProcessing(id, fiber))
          yield ()

        new JsonRpcServer[F]:
          override def handler: Pipe[F, JsonRpc.Message, JsonRpc.Message] = _.flatMap {
            case request: JsonRpc.Request =>
              Codec.fromJsonRpc(request) match {
                case Right((id, request: ClientRequest)) =>
                  fs2.Stream.exec(processRequest(id, request))
                case Right((id, _)) =>
                  fs2.Stream.emit(JsonRpc.Response.Error(
                    request.id,
                    ErrorCode.MethodNotFound,
                    s"Expected client request but got server request ${request.method} (request id: $id)",
                    None,
                  ))
                case Left(e) =>
                  fs2.Stream.emit(JsonRpc.Response.Error(request.id, ErrorCode.ParseError, e.getMessage, None))
              }
            case response: JsonRpc.Response =>
              fs2.Stream.exec(stateRef.get.flatMap(_.handleResponse(response)))
            case notification: JsonRpc.Notification =>
              fs2.Stream.exec(Codec.fromJsonRpc(notification) match {
                case Right(cancel: Cancelled) =>
                  stateRef.get.flatMap(_.cancelResponseProcessing(cancel.requestId))
                case Right(notification: ClientNotification) =>
                  server.handleNotification(notification)
                case Right(other) =>
                  onError(DecodingFailure(
                    s"Expected client notification but got server notification ${other.method.key}",
                    List.empty,
                  ))
                case Left(e) =>
                  onError(e)
              })
          }
          override def out: fs2.Stream[F, JsonRpc.Message] =
            fs2.Stream.fromQueueUnterminated(outQueue)
      )
  end start

  private class Comms[F[_]: Async](stateRef: Ref[F, State[F]], out: Queue[F, JsonRpc.Message]) extends Communication[F]:
    private def newRequestId[F[_]: Sync]: F[RequestId] =
      Sync[F].delay(UUID.randomUUID().toString).map(RequestId.IdString(_))
    override def request(request: ServerRequest)(using
      Decoder[request.Response]
    ): F[Either[McpError, request.Response]] =
      for
        id <- newRequestId
        jsonRpcId = id.toJsonRpc
        deferred <- Deferred[F, Either[McpError, request.Response]]
        handler: RequestResponseHandler[F] = {
          case m @ JsonRpc.Response.Success(`jsonRpcId`, response) =>
            val result = Codec.fromJsonRpc(m).map(_.asInstanceOf[request.Response])
              .left.map(e => McpError(ErrorCode.ParseError, "Failed to parse response: " + e.message, None))
            deferred.complete(result).void
          case JsonRpc.Response.Error(`jsonRpcId`, errorCode, errorMessage, errorData) =>
            deferred.complete(Left(McpError(errorCode, errorMessage, errorData))).void
          case other =>
            Sync[F].unit // id mismatch, ignore
        }
        _ <- stateRef.update(state => state.registerRequest(id, handler))
        _ <- out.offer(Codec.encodeServerRequest(id, request))
        result <- deferred.get.onCancel(out.offer(Codec.encodeServerNotification(Cancelled(id, "Cancelled"))))
      yield result

    override def notify(notification: ServerNotification): F[Unit] =
      out.offer(Codec.encodeServerNotification(notification))
  end Comms
