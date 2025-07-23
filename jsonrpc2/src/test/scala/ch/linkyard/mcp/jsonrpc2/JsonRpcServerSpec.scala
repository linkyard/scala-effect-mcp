package ch.linkyard.mcp.jsonrpc2

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import ch.linkyard.mcp.jsonrpc2.JsonRpc.*
import fs2.Pipe
import fs2.Stream
import io.circe.JsonObject
import org.scalatest.EitherValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class JsonRpcServerSpec extends AnyFunSpec with Matchers with EitherValues {
  describe("JsonRpcServer.start") {
    // Mock JsonRpcConnection for testing
    case class MockJsonRpcConnection(
      inStream: Stream[IO, MessageEnvelope],
      outPipe: Pipe[IO, Message, Unit],
      outMessages: Ref[IO, List[Message]],
    ) extends JsonRpcConnection[IO] {
      def in: Stream[IO, MessageEnvelope] = inStream
      def out: Pipe[IO, Message, Unit] = outPipe
    }

    // Mock JsonRpcServer for testing
    case class MockJsonRpcServer(
      handlerPipe: Pipe[IO, MessageEnvelope, Message],
      outStream: Stream[IO, Message],
    ) extends JsonRpcServer[IO] {
      def handler: Pipe[IO, MessageEnvelope, Message] = handlerPipe
      def out: Stream[IO, Message] = outStream
    }

    def createMockConnection(
      inMessages: List[Message],
      outMessages: Ref[IO, List[Message]],
    ): MockJsonRpcConnection = {
      val inStream = Stream.emits(inMessages.map(_.withoutAuth))
      val outPipe: Pipe[IO, Message, Unit] = stream =>
        stream.evalMap(msg => outMessages.update(_ :+ msg))
      MockJsonRpcConnection(inStream, outPipe, outMessages)
    }

    def createMockServer(
      handlerResponses: Map[Message, Message] = Map.empty,
      outMessages: List[Message] = Nil,
    ): MockJsonRpcServer = {
      val handlerPipe: Pipe[IO, MessageEnvelope, Message] = stream =>
        stream.map(msg => handlerResponses.getOrElse(msg.message, msg.message))
      val outStream = Stream.emits(outMessages)
      MockJsonRpcServer(handlerPipe, outStream)
    }

    it("should process incoming messages through handler and send to connection out") {
      val request = Request(Id.IdString("1"), "test", None)
      val response = Response.Success(Id.IdString("1"), JsonObject.empty)
      val test = for {
        outMessages <- Ref[IO].of(List.empty[Message])
        connection = createMockConnection(List(request), outMessages)
        server = createMockServer(Map(request -> response))
        connectionResource = Resource.pure[IO, JsonRpcConnection[IO]](connection)
        _ <- JsonRpcServer.start(server, connectionResource).use(IO.pure)
        finalMessages <- outMessages.get
      } yield finalMessages
      val messages = test.unsafeRunSync()
      messages.should(contain(response))
    }

    it("should send server out messages to connection out") {
      val serverOutMessage = Notification("server.notification", None)
      val test = for {
        outMessages <- Ref[IO].of(List.empty[Message])
        connection = createMockConnection(Nil, outMessages)
        server = createMockServer(outMessages = List(serverOutMessage))
        connectionResource = Resource.pure[IO, JsonRpcConnection[IO]](connection)
        _ <- JsonRpcServer.start(server, connectionResource).use(IO.pure)
        finalMessages <- outMessages.get
      } yield finalMessages
      val messages = test.unsafeRunSync()
      messages.should(contain(serverOutMessage))
    }

    it("should merge both incoming and outgoing message streams") {
      val request = Request(Id.IdString("1"), "test", None)
      val response = Response.Success(Id.IdString("1"), JsonObject.empty)
      val serverOutMessage = Notification("server.notification", None)
      val test = for {
        outMessages <- Ref[IO].of(List.empty[Message])
        connection = createMockConnection(List(request), outMessages)
        server = createMockServer(
          handlerResponses = Map(request -> response),
          outMessages = List(serverOutMessage),
        )
        connectionResource = Resource.pure[IO, JsonRpcConnection[IO]](connection)
        _ <- JsonRpcServer.start(server, connectionResource).use(IO.pure)
        finalMessages <- outMessages.get
      } yield finalMessages
      val messages = test.unsafeRunSync()
      messages.should(contain(response))
      messages.should(contain(serverOutMessage))
      messages.should(have size 2)
    }
  }
}
