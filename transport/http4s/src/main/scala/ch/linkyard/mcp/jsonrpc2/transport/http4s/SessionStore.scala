package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.Temporal
import cats.effect.kernel.Async
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.syntax.spawn.*
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler

import scala.concurrent.duration.FiniteDuration

/** A legacy http session: the handler of the connection and what is needed to close it. */
case class Session[F[_]](
  id: SessionId,
  handler: JsonRpcHandler[F],
  info: JsonRpcConnection.Info.Http,
  release: F[Unit],
)

trait SessionStore[F[_]]:
  /** Registers the session, `release` is run when the session is closed (explicitly or after the idle timeout). */
  def open(session: Session[F]): F[Unit]

  /** The session, a lookup restarts its idle timeout. */
  def get(id: SessionId): F[Option[Session[F]]]

  def close(id: SessionId): F[Unit]

object SessionStore:
  private case class SessionEntry[F[_]: Async](
    session: Session[F],
    timeoutFiber: Ref[F, Fiber[F, Throwable, Unit]],
  ):
    def updateTimeout(fibre: Fiber[F, Throwable, Unit]): F[Unit] =
      timeoutFiber.getAndSet(fibre).flatMap(_.cancel)
  end SessionEntry

  def inMemory[F[_]: Async](idleTimeout: FiniteDuration): Resource[F, SessionStore[F]] =
    Resource.eval(Ref.of[F, Map[SessionId, SessionEntry[F]]](Map.empty)).map { ref =>
      new SessionStore[F]:
        override def open(session: Session[F]): F[Unit] =
          for
            fiber <- startTimeoutFiber(session.id)
            fiberRef <- Ref.of[F, Fiber[F, Throwable, Unit]](fiber)
            _ <- ref.update(_ + (session.id -> SessionEntry(session, fiberRef)))
          yield ()

        override def get(id: SessionId): F[Option[Session[F]]] =
          ref.get.map(_.get(id))
            .flatMap(_.traverse(e => startTimeoutFiber(id).flatMap(e.updateTimeout).as(e.session)))

        override def close(id: SessionId): F[Unit] =
          remove(id).flatMap(_.traverse_(entry => entry.session.release >> entry.timeoutFiber.get.flatMap(_.cancel)))

        private def remove(id: SessionId): F[Option[SessionEntry[F]]] =
          ref.modify(sessions => (sessions - id, sessions.get(id)))

        // the timeout fiber must not cancel itself
        private def startTimeoutFiber(id: SessionId): F[Fiber[F, Throwable, Unit]] =
          (Temporal[F].sleep(idleTimeout) >> remove(id).flatMap(_.traverse_(_.session.release))).start

        override def toString(): String = "SessionStore.InMemory"
    }
  end inMemory
