package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.Temporal
import cats.effect.kernel.Async
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.syntax.spawn.*
import cats.implicits.*

import scala.concurrent.duration.FiniteDuration

trait SessionStore[F[_]]:
  def open(session: StatefulConnection[F], cleanup: F[Unit]): F[Unit]
  def get(id: SessionId): F[Option[StatefulConnection[F]]]
  def close(id: SessionId): F[Unit]

object SessionStore:
  private case class SessionEntry[F[_]: Async](
    connection: StatefulConnection[F],
    cleanup: F[Unit],
    timeoutFiber: Ref[F, Fiber[F, Throwable, Unit]],
  ):
    def updateTimeout(fibre: Fiber[F, Throwable, Unit]): F[Unit] =
      timeoutFiber.get.flatMap(_.cancel) >> timeoutFiber.set(fibre)
  end SessionEntry

  def inMemory[F[_]: Async](idleTimeout: FiniteDuration): Resource[F, SessionStore[F]] =
    Resource.eval(Ref.of[F, Map[SessionId, SessionEntry[F]]](Map.empty)).map { ref =>
      new SessionStore[F]:
        override def open(session: StatefulConnection[F], cleanup: F[Unit]): F[Unit] =
          for {
            fiber <- startTimeoutFiber(session.sessionId)
            fiberRef <- Ref.of[F, Fiber[F, Throwable, Unit]](fiber)
            _ <- ref.update(_ + (session.sessionId -> SessionEntry(session, cleanup, fiberRef)))
          } yield ()

        override def get(id: SessionId): F[Option[StatefulConnection[F]]] =
          ref.modify(sessions => sessions -> sessions.get(id))
            .flatMap(_.traverse(e => startTimeoutFiber(id).flatMap(e.updateTimeout).as(e.connection)))

        override def close(id: SessionId): F[Unit] =
          ref.modify(sessions => (sessions - id, sessions.get(id)))
            .flatMap {
              case Some(entry) =>
                entry.timeoutFiber.get.flatMap(_.cancel) >> entry.cleanup
              case None => Async[F].unit
            }

        private def startTimeoutFiber(id: SessionId): F[Fiber[F, Throwable, Unit]] =
          (Temporal[F].sleep(idleTimeout) >> close(id)).start

        override def toString(): String = "SessionStore.InMemory"
    }
  end inMemory
