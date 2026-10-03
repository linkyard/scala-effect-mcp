package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import cats.effect.Ref
import cats.effect.kernel.Resource
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandlerFactory
import fs2.Stream

/** Records what it receives, the messages for requests are configurable. */
class FakeHandler(
  val respond: JsonRpc.Request => Stream[IO, JsonRpc.Message],
  val unsolicitedMessages: Stream[IO, JsonRpc.Message],
  val requests: Ref[IO, List[(JsonRpc.Request, JsonRpcHandler.Context)]],
  val notifications: Ref[IO, List[(JsonRpc.Notification, JsonRpcHandler.Context)]],
  val responses: Ref[IO, List[(JsonRpc.Response, JsonRpcHandler.Context)]],
) extends JsonRpcHandler[IO]:
  override def request(request: JsonRpc.Request, context: JsonRpcHandler.Context): Stream[IO, JsonRpc.Message] =
    Stream.eval(requests.update(_ :+ (request -> context))) >> respond(request)
  override def notification(notification: JsonRpc.Notification, context: JsonRpcHandler.Context): IO[Unit] =
    notifications.update(_ :+ (notification -> context))
  override def response(response: JsonRpc.Response, context: JsonRpcHandler.Context): IO[Unit] =
    responses.update(_ :+ (response -> context))
  override def unsolicited: Stream[IO, JsonRpc.Message] = unsolicitedMessages
  override def cancelledRequest(notification: JsonRpc.Notification): Option[JsonRpc.Id] = None

object FakeHandler:
  def create(
    respond: JsonRpc.Request => Stream[IO, JsonRpc.Message],
    unsolicited: Stream[IO, JsonRpc.Message] = Stream.empty,
  ): IO[FakeHandler] =
    for
      requests <- Ref.of[IO, List[(JsonRpc.Request, JsonRpcHandler.Context)]](Nil)
      notifications <- Ref.of[IO, List[(JsonRpc.Notification, JsonRpcHandler.Context)]](Nil)
      responses <- Ref.of[IO, List[(JsonRpc.Response, JsonRpcHandler.Context)]](Nil)
    yield FakeHandler(respond, unsolicited, requests, notifications, responses)

/** Provides a stateless handler and (if sessions are supported) a handler for every connection. */
class FakeFactory(
  val statelessHandler: FakeHandler,
  val sessionHandler: Option[FakeHandler],
  val connections: Ref[IO, List[JsonRpcConnection.Info]],
  val released: Ref[IO, Int],
) extends JsonRpcHandlerFactory[IO]:
  override def stateless: JsonRpcHandler[IO] = statelessHandler
  override def supportsSessions: Boolean = sessionHandler.isDefined
  override def connection(info: JsonRpcConnection.Info): Resource[IO, JsonRpcHandler[IO]] =
    Resource.make(connections.update(_ :+ info).as(sessionHandler.get: JsonRpcHandler[IO]))(_ =>
      released.update(_ + 1)
    )

object FakeFactory:
  def create(stateless: FakeHandler, sessionHandler: Option[FakeHandler] = None): IO[FakeFactory] =
    for
      connections <- Ref.of[IO, List[JsonRpcConnection.Info]](Nil)
      released <- Ref.of[IO, Int](0)
    yield FakeFactory(stateless, sessionHandler, connections, released)
