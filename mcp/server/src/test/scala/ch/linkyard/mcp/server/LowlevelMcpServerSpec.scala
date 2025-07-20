package ch.linkyard.mcp.server

import cats.effect.IO
import cats.effect.Ref
import cats.effect.kernel.Deferred
import cats.effect.std.Queue
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Tool
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Json
import io.circe.JsonObject
import io.circe.literal.json
import io.circe.syntax.*
import org.scalatest.OptionValues
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

class LowlevelMcpServerSpec extends AsyncFunSpec with AsyncIOSpec with Matchers with OptionValues:
  describe("LowlevelMcpServer") {

    class Controller(
      serverRequestIn: Ref[IO, List[(ClientRequest, Deferred[IO, ServerResponse | Exception])]],
      serverNotificationIn: Ref[IO, List[ClientNotification]],
      serverErrorsIn: Ref[IO, List[DecodingFailure]],
      serverCancelled: Ref[IO, List[ClientRequest]],
      clientIn: Ref[IO, List[JsonRpc.Message]],
      clientOut: Queue[IO, JsonRpc.Message],
      comms: LowlevelMcpServer.Communication[IO],
    ):
      def sendFromClient(json: JsonRpc.Message): IO[Unit] = clientOut.offer(json)
      def sendFromClient(message: (RequestId, ClientRequest) | ClientNotification): IO[Unit] =
        sendFromClient(message match
          case (id, req: ClientRequest) => Codec.encodeClientRequest(id, req)
          case not: ClientNotification  => Codec.encodeClientNotification(not))

      def clientReceived: IO[List[JsonRpc.Message]] = tick >> clientIn.getAndSet(List.empty)

      def handleRequestFromClient(req: ClientRequest, response: IO[ServerResponse | Exception]): IO[Unit] =
        for
          _ <- tick
          (request, deferred) <-
            serverRequestIn.get.map(_.find(_._1 == req).getOrElse(throw new Exception("request not found")))
          _ <- serverRequestIn.update(l => l.filter(_._1 != req))
          _ <- response.flatMap(r => deferred.complete(r))
        yield ()
      def handleRequestFromClientByDoingNothing(req: ClientRequest): IO[Unit] =
        for
          _ <- tick
          (request, deferred) <-
            serverRequestIn.get.map(_.find(_._1 == req).getOrElse(throw new Exception("request not found")))
          _ <- serverRequestIn.update(l => l.filter(_._1 != req))
        yield ()

      def assertNoPending: IO[Unit] =
        for
          _ <- tick
          _ <- clientIn.get.asserting(_ shouldBe empty)
          _ <- serverRequestIn.get.asserting(_ shouldBe empty)
          _ <- serverNotificationIn.get.asserting(_ shouldBe empty)
          _ <- serverErrorsIn.get.asserting(_ shouldBe empty)
        yield ()

      def pendingNotifications: IO[List[ClientNotification]] =
        tick >> serverNotificationIn.getAndSet(List.empty)

      def pendingCancelled: IO[List[ClientRequest]] =
        tick >> serverCancelled.getAndSet(List.empty)

      def notifyFromServer(notification: ServerNotification): IO[Unit] = comms.notify(notification)
      def requestFromServer(request: ServerRequest)(using
        Decoder[request.Response]
      ): IO[Deferred[IO, Either[McpError, request.Response]]] =
        for
          response <- Deferred[IO, Either[McpError, request.Response]]
          _ <- comms.request(request).flatMap(response.complete).start
        yield response

      def requestFromServerAndCancel(request: ServerRequest)(using Decoder[request.Response]): IO[Unit] =
        for
          response <- Deferred[IO, Either[McpError, request.Response]]
          f <- comms.request(request).flatMap(response.complete).start
          _ <- tick
          _ <- f.cancel
        yield ()

      private def tick: IO[Unit] = IO.sleep(50.millis)
    end Controller

    def withServer[F, A](f: Controller => IO[A]) =
      for
        serverRequestIn <- Ref[IO].of(List.empty[(ClientRequest, Deferred[IO, ServerResponse | Exception])])
        serverNotificationIn <- Ref[IO].of(List.empty[ClientNotification])
        serverErrors <- Ref[IO].of(List.empty[DecodingFailure])
        serverCancelled <- Ref[IO].of(List.empty[ClientRequest])
        commsDeferred <- Deferred[IO, LowlevelMcpServer.Communication[IO]]
        jsonRpcServer <- LowlevelMcpServer.start[IO](
          comms =>
            cats.effect.Resource.eval(commsDeferred.complete(comms).void).map(_ =>
              new LowlevelMcpServer[IO]:
                override def handleRequest(request: ClientRequest): IO[ServerResponse] =
                  for
                    deferred <- Deferred[IO, ServerResponse | Exception]
                    _ <- serverRequestIn.update(_ :+ (request, deferred))
                    response <- deferred.get.onCancel(serverCancelled.update(_ :+ request))
                    result <- response match
                      case response: ServerResponse => IO.pure(response)
                      case error: Exception         => IO.raiseError(error)
                  yield result
                override def handleNotification(notification: ClientNotification): IO[Unit] =
                  serverNotificationIn.update(_ :+ notification)
            ),
          onError = e => serverErrors.update(_ :+ e),
        ).use(_.pure)
        comms <- commsDeferred.get
        clientIn <- Ref[IO].of(List.empty[JsonRpc.Message])
        clientReader <- jsonRpcServer.out.evalMap(m => clientIn.update(_ :+ m)).compile.drain.start
        clientOut <- Queue.unbounded[IO, JsonRpc.Message]
        clientWriter <- jsonRpcServer.handler(fs2.Stream.fromQueueUnterminated(clientOut)).compile.drain.start
        controller =
          Controller(serverRequestIn, serverNotificationIn, serverErrors, serverCancelled, clientIn, clientOut, comms)
        result <- f(controller)
        _ <- clientReader.cancel
        _ <- clientWriter.cancel
        _ <- controller.assertNoPending
      yield result

    it("should send a notification to the client")(withServer { controller =>
      for
        _ <- controller.notifyFromServer(Tool.ListChanged())
        result <- controller.clientReceived.asserting { l =>
          l.length shouldBe 1
          l.head shouldBe JsonRpc.Notification("notifications/tools/list_changed", None)
        }
      yield result
    })

    it("should receive a notification from the client (jsonrpc)")(withServer { controller =>
      for
        _ <- controller.sendFromClient(JsonRpc.Notification("notifications/initialized", None))
        result <- controller.pendingNotifications.asserting { l =>
          l.length shouldBe 1
          l.head shouldBe Initialized()
        }
      yield result
    })

    it("should receive a notification from the client")(withServer { controller =>
      for
        _ <- controller.sendFromClient(Initialized())
        result <- controller.pendingNotifications.asserting { l =>
          l.length shouldBe 1
          l.head shouldBe Initialized()
        }
      yield result
    })

    it("should handle a request with ok")(withServer { controller =>
      for
        _ <- controller.sendFromClient((RequestId.IdNumber(1), Tool.ListTools(None)))
        _ <- controller.handleRequestFromClient(
          Tool.ListTools(None),
          Tool.ListTools.Response(List.empty, Some("test")).pure,
        )
        result <- controller.clientReceived.asserting { l =>
          l.length shouldBe 1
          l.head shouldBe JsonRpc.Response.Success(
            JsonRpc.Id.IdInt(1),
            json"""{"tools":[], "nextCursor": "test"}""".asObject.get,
          )
        }
      yield result
    })

    it("should handle a request with mcp error")(withServer { controller =>
      for
        _ <- controller.sendFromClient((RequestId.IdNumber(2), Tool.ListTools(None)))
        _ <- controller.handleRequestFromClient(
          Tool.ListTools(None),
          McpError.McpErrorException(McpError(ErrorCode.InvalidParams, "invalid params", "data".asJson.some)).pure,
        )
        result <- controller.clientReceived.asserting { l =>
          l.length shouldBe 1
          l.head shouldBe JsonRpc.Response.Error(
            JsonRpc.Id.IdInt(2),
            ErrorCode.InvalidParams,
            "invalid params",
            Some("data".asJson),
          )
        }
      yield result
    })

    it("should handle a request with generic error")(withServer { controller =>
      for
        _ <- controller.sendFromClient((RequestId.IdNumber(2), Tool.ListTools(None)))
        _ <- controller.handleRequestFromClient(Tool.ListTools(None), RuntimeException("fehlerhaft").pure)
        result <- controller.clientReceived.asserting { l =>
          l.length shouldBe 1
          l.head shouldBe JsonRpc.Response.Error(JsonRpc.Id.IdInt(2), ErrorCode.InternalError, "Internal error", None)
        }
      yield result
    })

    it("should handle a request that gets cancelled")(withServer { controller =>
      for
        _ <- controller.sendFromClient((RequestId.IdNumber(3), Tool.ListTools(None)))
        f <- controller.handleRequestFromClient(
          Tool.ListTools(None),
          IO.sleep(5.seconds) >> Tool.ListTools.Response(List.empty, Some("should not be sent")).pure,
        ).start
        _ <- controller.sendFromClient(Cancelled(RequestId.IdNumber(3), "abort", None))
        _ <- controller.pendingCancelled.asserting(_ should have size 1)
        _ <- f.cancel // this does not get cancelled because of the way the test is written (via deferrable), so we need to cancel it manually
        result <- controller.clientReceived.asserting(_ shouldBe empty)
      yield result
    })

    it("should handle a request that get cancelled before anything is done")(withServer { controller =>
      for
        _ <- controller.sendFromClient((RequestId.IdNumber(4), Tool.ListTools(None)))
        _ <- IO.sleep(100.millis)
        _ <- controller.sendFromClient(Cancelled(RequestId.IdNumber(4), "abort", None))
        _ <- controller.handleRequestFromClientByDoingNothing(Tool.ListTools(None)).attempt.void
        result <- controller.clientReceived.asserting(_ shouldBe empty)
      yield result
    })

    it("should send a request to the client and get a successful response")(withServer { controller =>
      for
        respDef <- controller.requestFromServer(Ping())
        in <- controller.clientReceived
        _ = in should have size 1
        msg = in.headOption.collect { case msg: JsonRpc.Request => msg }.value
        _ = msg.method shouldBe "ping"
        _ <- respDef.tryGet.asserting(_ shouldBe empty)
        _ <- controller.sendFromClient(JsonRpc.Response.Success(msg.id, JsonObject.empty))
        result <- respDef.get.asserting(_ shouldBe Right(Ping.Response())).timeout(1.second)
      yield result
    })

    it("should send a request to the client and get an error response")(withServer { controller =>
      for
        respDef <- controller.requestFromServer(Ping())
        in <- controller.clientReceived
        _ = in should have size 1
        msg = in.headOption.collect { case msg: JsonRpc.Request => msg }.value
        _ = msg.method shouldBe "ping"
        _ <- respDef.tryGet.asserting(_ shouldBe empty)
        _ <- controller.sendFromClient(JsonRpc.Response.Error(msg.id, ErrorCode.InternalError, "expected error", None))
        result <- respDef.get.asserting(_ shouldBe Left(McpError(
          ErrorCode.InternalError,
          "expected error",
          None,
        ))).timeout(1.second)
      yield result
    })

    it("should send a request to the client and then cancel it")(withServer { controller =>
      for
        _ <- controller.requestFromServerAndCancel(Ping())
        in <- controller.clientReceived
        _ = in should have size 2
        msg = in.headOption.collect { case msg: JsonRpc.Request => msg }.value
        _ = msg.method shouldBe "ping"
        cancel = in(1).some.collect { case msg: JsonRpc.Notification => msg }.value
        _ = cancel.method shouldBe "notifications/cancelled"
        _ = cancel.params.value shouldBe Json.obj(
          "requestId" -> msg.id.asJson,
          "reason" -> Json.fromString("Cancelled"),
        ).asObject.value
      yield ()
    })
  }
