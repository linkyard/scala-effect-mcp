package ch.linkyard.mcp.jsonrpc2

import cats.effect.implicits.*
import cats.effect.kernel.Concurrent
import cats.effect.kernel.Deferred
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.implicits.*
import fs2.Stream

object JsonRpcServer:
  /** Serves a connection with the handler until the input of the connection ends. Every request is handled
    * concurrently, its messages are sent to the connection as they are produced.
    */
  def run[F[_]: Concurrent](handler: JsonRpcHandler[F], connection: JsonRpcConnection[F]): F[Unit] =
    run(handler, connection, _ => Concurrent[F].unit)

  /** `onError` is called with the errors of the handler (the client gets an internal error). */
  def run[F[_]: Concurrent](
    handler: JsonRpcHandler[F],
    connection: JsonRpcConnection[F],
    onError: Throwable => F[Unit],
  ): F[Unit] =
    for
      out <- Queue.unbounded[F, JsonRpc.Message]
      running <- Ref.of[F, Map[JsonRpc.Id, Fiber[F, Throwable, Unit]]](Map.empty)
      _ <- loop(handler, connection, onError, out, running)
    yield ()

  private def loop[F[_]: Concurrent](
    handler: JsonRpcHandler[F],
    connection: JsonRpcConnection[F],
    onError: Throwable => F[Unit],
    out: Queue[F, JsonRpc.Message],
    running: Ref[F, Map[JsonRpc.Id, Fiber[F, Throwable, Unit]]],
  ): F[Unit] =
    def contextOf(envelope: JsonRpc.MessageEnvelope) = JsonRpcHandler.Context(envelope.auth, connection.info)

    def internalError(id: JsonRpc.Id): JsonRpc.Message =
      JsonRpc.Response.Error(id, JsonRpc.ErrorCode.InternalError, "Internal error", None)

    def startRequest(request: JsonRpc.Request, context: JsonRpcHandler.Context): F[Unit] =
      val messages = handler.request(request, context)
        .handleErrorWith(e => Stream.eval(onError(e)) >> Stream.emit(internalError(request.id)))
        .evalMap(out.offer)
        .compile.drain
      for
        gate <- Deferred[F, Unit]
        fiber <- (gate.get >> messages).guarantee(running.update(_ - request.id)).start
        _ <- running.update(_ + (request.id -> fiber))
        _ <- gate.complete(())
      yield ()

    def cancel(id: JsonRpc.Id): F[Unit] =
      running.modify(r => (r - id, r.get(id))).flatMap(_.traverse_(_.cancel))

    val input = connection.in.evalMap { envelope =>
      envelope.message match
        case request: JsonRpc.Request => startRequest(request, contextOf(envelope))
        case notification: JsonRpc.Notification =>
          handler.cancelledRequest(notification) match
            case Some(id) => cancel(id)
            case None     => handler.notification(notification, contextOf(envelope))
        case response: JsonRpc.Response => handler.response(response, contextOf(envelope))
    }
    val output = Stream.fromQueueUnterminated(out).merge(handler.unsolicited).through(connection.out)

    input.concurrently(output).compile.drain
      .guarantee(running.getAndSet(Map.empty).flatMap(_.values.toList.traverse_(_.cancel)))
  end loop

  /** Serves a connection until its input ends, with a handler that lives as long as the connection. */
  def serve[F[_]: Concurrent](
    factory: JsonRpcHandlerFactory[F],
    connection: JsonRpcConnection[F],
    onError: Throwable => F[Unit],
  ): F[Unit] =
    factory.connection(connection.info).use(handler => run(handler, connection, onError))
