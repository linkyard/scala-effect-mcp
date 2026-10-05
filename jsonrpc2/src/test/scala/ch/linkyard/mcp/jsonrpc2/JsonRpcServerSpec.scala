package ch.linkyard.mcp.jsonrpc2

import cats.effect.IO
import cats.effect.Ref
import cats.effect.kernel.Deferred
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import ch.linkyard.mcp.jsonrpc2.JsonRpc.*
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection.Info
import fs2.Pipe
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

class JsonRpcServerSpec extends AnyFunSpec with Matchers:
  private val CancelMethod = "cancel"

  private def request(id: Int, method: String = "test"): Request = Request(Id.IdInt(id), method, None)
  private def response(id: Int): Response = Response.Success(Id.IdInt(id), JsonObject.empty)
  private def cancel(id: Int): Notification = Notification(CancelMethod, Some(JsonObject("id" -> id.asJson)))

  private class Connection(inputQueue: Queue[IO, Option[MessageEnvelope]], val sent: Queue[IO, Message])
      extends JsonRpcConnection[IO]:
    def info: Info = Info.Other(Map.empty)
    def in: Stream[IO, MessageEnvelope] = Stream.fromQueueNoneTerminated(inputQueue)
    def out: Pipe[IO, Message, Unit] = _.evalMap(sent.offer)

  private class Handler(
    onRequest: Request => Stream[IO, Message],
    val notifications: Ref[IO, List[Notification]],
    val responses: Ref[IO, List[Response]],
    val unsolicited: Stream[IO, Message] = Stream.empty,
  ) extends JsonRpcHandler[IO]:
    def request(request: Request, context: JsonRpcHandler.Context): Stream[IO, Message] = onRequest(request)
    def notification(notification: Notification, context: JsonRpcHandler.Context): IO[Unit] =
      notifications.update(_ :+ notification)
    def response(response: Response, context: JsonRpcHandler.Context): IO[Unit] = responses.update(_ :+ response)
    def cancelledRequest(notification: Notification): Option[Id] =
      if notification.method == CancelMethod then
        notification.params.flatMap(_("id")).flatMap(_.as[Id].toOption)
      else None

  /** Runs the server while the test sends messages, returns when the input of the connection was closed. */
  private class Harness(
    handler: Handler,
    input: Queue[IO, Option[MessageEnvelope]],
    connection: Connection,
    errors: Ref[IO, List[Throwable]],
  ):
    def send(message: Message): IO[Unit] = input.offer(Some(message.withoutAuth))
    def received: IO[Message] = connection.sent.take.timeout(5.seconds)
    def receivedAll(count: Int): IO[List[Message]] = List.fill(count)(received).sequence
    def close: IO[Unit] = input.offer(None)
    def errorsSoFar: IO[List[Throwable]] = errors.get
    def notifications: IO[List[Notification]] = handler.notifications.get
    def responses: IO[List[Response]] = handler.responses.get

  private def withServer[A](
    onRequest: Request => Stream[IO, Message],
    unsolicited: Stream[IO, Message] = Stream.empty,
  )(test: Harness => IO[A]): A =
    val program =
      for
        input <- Queue.unbounded[IO, Option[MessageEnvelope]]
        sent <- Queue.unbounded[IO, Message]
        notifications <- Ref.of[IO, List[Notification]](Nil)
        responses <- Ref.of[IO, List[Response]](Nil)
        errors <- Ref.of[IO, List[Throwable]](Nil)
        handler = Handler(onRequest, notifications, responses, unsolicited)
        connection = Connection(input, sent)
        server <- JsonRpcServer.run(handler, connection, e => errors.update(_ :+ e)).start
        result <- test(Harness(handler, input, connection, errors))
        _ <- input.offer(None)
        _ <- server.join.timeout(5.seconds)
      yield result
    program.timeout(20.seconds).unsafeRunSync()

  import cats.implicits.*

  describe("JsonRpcServer.run") {
    it("should send the messages of a request in order") {
      val notification = Notification("progress", None)
      val messages = withServer(_ => Stream(notification, response(1))) { h =>
        h.send(request(1)) >> h.receivedAll(2)
      }
      messages shouldBe List(notification, response(1))
    }

    it("should handle requests concurrently") {
      val result = withServer(r => if r.id == Id.IdInt(1) then Stream.never[IO] else Stream.emit(response(2))) { h =>
        h.send(request(1)) >> h.send(request(2)) >> h.received
      }
      result shouldBe response(2)
    }

    it("should cancel a request when the client cancels it") {
      val cancelled = Deferred.unsafe[IO, Unit]
      val started = Deferred.unsafe[IO, Unit]
      val outcome = withServer(_ =>
        Stream.exec(started.complete(()).void) ++ Stream.never[IO].onFinalize(cancelled.complete(()).void)
      ) {
        h => h.send(request(1)) >> started.get >> h.send(cancel(1)) >> cancelled.get.timeout(5.seconds).as("cancelled")
      }
      outcome shouldBe "cancelled"
    }

    it("should not pass the cancellation on to the handler") {
      val started = Deferred.unsafe[IO, Unit]
      val notifications = withServer(_ => Stream.exec(started.complete(()).void) ++ Stream.never[IO]) { h =>
        h.send(request(1)) >> started.get >> h.send(cancel(1)) >> h.send(Notification("other", None)) >>
          h.send(request(2, "ping")).void >> IO.sleep(200.millis) >> h.notifications
      }
      notifications.map(_.method) shouldBe List("other")
    }

    it("should pass notifications and responses of the client to the handler") {
      val (notifications, responses) = withServer(_ => Stream.empty) { h =>
        h.send(Notification("n", None)) >> h.send(response(5)) >> IO.sleep(200.millis) >>
          (h.notifications, h.responses).tupled
      }
      notifications shouldBe List(Notification("n", None))
      responses shouldBe List(response(5))
    }

    it("should send the unsolicited messages") {
      val message = Notification("unsolicited", None)
      val received = withServer(_ => Stream.empty, unsolicited = Stream.emit(message)) { h => h.received }
      received shouldBe message
    }

    it("should answer with an internal error when the handler fails") {
      val boom = new RuntimeException("boom")
      val (message, errors) = withServer(_ => Stream.raiseError[IO](boom)) { h =>
        h.send(request(1)) >> h.received.product(h.errorsSoFar)
      }
      message shouldBe Response.Error(Id.IdInt(1), ErrorCode.InternalError, "Internal error", None)
      errors shouldBe List(boom)
    }

    it("should cancel the open requests when the connection ends") {
      val cancelled = Deferred.unsafe[IO, Unit]
      val started = Deferred.unsafe[IO, Unit]
      val program =
        for
          input <- Queue.unbounded[IO, Option[MessageEnvelope]]
          sent <- Queue.unbounded[IO, Message]
          notifications <- Ref.of[IO, List[Notification]](Nil)
          responses <- Ref.of[IO, List[Response]](Nil)
          handler = Handler(
            _ => Stream.exec(started.complete(()).void) ++ Stream.never[IO].onFinalize(cancelled.complete(()).void),
            notifications,
            responses,
          )
          _ <- input.offer(Some(request(1).withoutAuth))
          server <- JsonRpcServer.run(handler, Connection(input, sent)).start
          _ <- started.get
          _ <- input.offer(None)
          _ <- server.join
          _ <- cancelled.get.timeout(5.seconds)
        yield "done"
      program.timeout(20.seconds).unsafeRunSync() shouldBe "done"
    }
  }
