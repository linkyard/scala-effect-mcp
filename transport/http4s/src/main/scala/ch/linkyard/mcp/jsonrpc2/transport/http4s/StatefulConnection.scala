package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import io.circe.syntax.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class StatefulConnection[F[_]: Async] private (
  val sessionId: SessionId,
  inQueue: Queue[F, JsonRpc.Message],
  outGeneric: Queue[F, JsonRpc.Message],
  outRequestRelated: Ref[F, Map[JsonRpc.Id, Queue[F, Option[JsonRpc.Message]]]],
  capacity: Int,
):
  private given Logger[F] = Slf4jLogger.getLogger[F].addContext(Map("sessionId" -> sessionId.asString))

  val connection: JsonRpcConnection[F] = new JsonRpcConnection[F]:
    override def out: fs2.Pipe[F, JsonRpc.Message, Unit] = _.evalMap(msg =>
      msg.relatesTo match
        case Some(callId) =>
          outQueue(callId).flatMap(queue =>
            msg match
              case r: JsonRpc.Response => queue.offer(msg.some) >> queue.offer(None) // last message, terminate the queue
              case other => queue.offer(msg.some)
          )

        case None => outGeneric.offer(msg)
    )
    override def in: fs2.Stream[F, JsonRpc.Message] = fs2.Stream.fromQueueUnterminated(inQueue)

  private def outQueue(id: JsonRpc.Id): F[Queue[F, Option[JsonRpc.Message]]] =
    outRequestRelated.get.map(_.get(id)).flatMap {
      case Some(q) => q.pure[F]
      case None    => createQueue(id)
    }

  private def createQueue(id: JsonRpc.Id): F[Queue[F, Option[JsonRpc.Message]]] =
    Queue.bounded[F, Option[JsonRpc.Message]](capacity).flatMap(q =>
      outRequestRelated.modify(queues =>
        queues.get(id) match
          case Some(existing) => (queues, existing)
          case None           => (queues + (id -> q), q)
      )
    )
  end createQueue

  private def closeQueue(id: JsonRpc.Id): F[Unit] =
    outRequestRelated.modify(queues =>
      val queue = queues.get(id)
      (queues - id, queue)
    )
      // enqueue all remaining messages to the generic queue
      .flatMap(_.traverse(a =>
        a.tryTakeN(capacity.some).flatMap(drained =>
          drained.traverse {
            case Some(msg) => outGeneric.tryOffer(msg).void
            case None      => Async[F].unit
          }
        )
      ))
      .void
  end closeQueue

  def receivedFromClient(message: JsonRpc.Message): F[Unit] =
    Logger[F].debug(s"Received message ${message.asJson.noSpaces}")
    inQueue.offer(message)

  def streamRequestReleated(id: JsonRpc.Id): fs2.Stream[F, JsonRpc.Message] =
    fs2.Stream.eval(outQueue(id))
      .flatMap(queue => fs2.Stream.fromQueueNoneTerminated(queue))
      .evalTap(msg => Logger[F].debug(s"Sending message related to request ${id}: ${msg.asJson.noSpaces}"))
      .onFinalize(closeQueue(id))

  def streamNonRequestRelated: fs2.Stream[F, JsonRpc.Message] =
    fs2.Stream.fromQueueUnterminated(inQueue)
      .evalTap(msg => Logger[F].debug(s"Sending non request related message: ${msg.asJson.noSpaces}"))

object StatefulConnection:
  def create[F[_]: Async](capacity: Int = 1000): F[StatefulConnection[F]] =
    for
      sessionId <- SessionId.generate
      inQueue <- Queue.bounded[F, JsonRpc.Message](capacity)
      outGeneric <- Queue.bounded[F, JsonRpc.Message](capacity)
      outRequestRelated <- Ref.of[F, Map[JsonRpc.Id, Queue[F, Option[JsonRpc.Message]]]](Map.empty)
      connection = new StatefulConnection[F](sessionId, inQueue, outGeneric, outRequestRelated, capacity)
    yield connection
