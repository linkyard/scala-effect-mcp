package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import ch.linkyard.mcp.jsonrpc2.transport.http4s.SessionStore
import ch.linkyard.mcp.jsonrpc2.transport.http4s.StatefulConnection
import org.scalatest.funspec.AnyFunSpec

import scala.concurrent.duration.*

class SessionStoreSpec extends AnyFunSpec {
  describe("SessionStore.InMemory") {
    it("should open, get, and close sessions") {
      val test = for {
        conn <- StatefulConnection.create[IO](1)
        cleanupCalled <- Ref.of[IO, Boolean](false)
        cleanup = cleanupCalled.set(true)
        storeR = SessionStore.inMemory[IO](10.seconds)
        _ <- storeR.use { store =>
          for {
            _ <- store.open(conn, cleanup)
            get1 <- store.get(conn.sessionId)
            _ = assert(get1.isDefined)
            _ <- store.close(conn.sessionId)
            get2 <- store.get(conn.sessionId)
            _ = assert(get2.isEmpty)
            called <- cleanupCalled.get
            _ = assert(called)
          } yield ()
        }
      } yield ()
      test.unsafeRunSync()
    }
    it("should remove session after idle timeout") {
      val test = for {
        conn <- StatefulConnection.create[IO](1)
        cleanupCalled <- Ref.of[IO, Boolean](false)
        cleanup = cleanupCalled.set(true)
        storeR = SessionStore.inMemory[IO](100.millis)
        _ <- storeR.use { store =>
          for {
            _ <- store.open(conn, cleanup)
            _ <- IO.sleep(500.millis)
            get <- store.get(conn.sessionId)
            _ = assert(get.isEmpty)
            called <- cleanupCalled.get
            _ = assert(called)
          } yield ()
        }
      } yield ()
      test.unsafeRunSync()
    }
  }
}
