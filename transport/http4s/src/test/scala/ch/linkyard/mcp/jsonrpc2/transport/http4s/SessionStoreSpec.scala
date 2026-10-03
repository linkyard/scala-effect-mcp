package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection.Info
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class SessionStoreSpec extends AnyFunSpec with Matchers:
  private def newSession(release: IO[Unit]): IO[Session[IO]] =
    for
      id <- SessionId.generate[IO]
      handler <- FakeHandler.create(_ => fs2.Stream.empty)
    yield Session(id, handler, Info.Http(None, None, Map.empty), release)

  describe("SessionStore.InMemory") {
    it("should open, get, and close sessions") {
      val test =
        for
          released <- Ref.of[IO, Boolean](false)
          session <- newSession(released.set(true))
          _ <- SessionStore.inMemory[IO](10.seconds).use { store =>
            for
              _ <- store.open(session)
              get1 <- store.get(session.id)
              _ = get1.map(_.id) shouldBe Some(session.id)
              _ <- store.close(session.id)
              get2 <- store.get(session.id)
              _ = get2 shouldBe None
              called <- released.get
              _ = called shouldBe true
            yield ()
          }
        yield ()
      test.unsafeRunTimed(10.seconds) shouldBe defined
    }

    it("should remove session after idle timeout") {
      val test =
        for
          released <- Ref.of[IO, Boolean](false)
          session <- newSession(released.set(true))
          _ <- SessionStore.inMemory[IO](100.millis).use { store =>
            for
              _ <- store.open(session)
              _ <- IO.sleep(500.millis)
              get <- store.get(session.id)
              _ = get shouldBe None
              called <- released.get
              _ = called shouldBe true
            yield ()
          }
        yield ()
      test.unsafeRunTimed(10.seconds) shouldBe defined
    }

    it("should refresh the idle timeout on get") {
      val test =
        for
          released <- Ref.of[IO, Boolean](false)
          session <- newSession(released.set(true))
          _ <- SessionStore.inMemory[IO](400.millis).use { store =>
            for
              _ <- store.open(session)
              _ <- IO.sleep(250.millis)
              _ <- store.get(session.id)
              _ <- IO.sleep(250.millis)
              stillThere <- store.get(session.id)
              _ = stillThere shouldBe defined
              _ <- released.get.map(_ shouldBe false)
            yield ()
          }
        yield ()
      test.unsafeRunTimed(10.seconds) shouldBe defined
    }
  }
