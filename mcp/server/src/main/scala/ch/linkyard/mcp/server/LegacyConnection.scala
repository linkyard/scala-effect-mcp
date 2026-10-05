package ch.linkyard.mcp.server

import cats.effect.implicits.*
import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.effect.std.Supervisor
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.McpCodec.DecodeError
import ch.linkyard.mcp.protocol.McpCodec.fromJsonRpc
import ch.linkyard.mcp.protocol.McpCodec.toJsonRpc
import ch.linkyard.mcp.protocol.legacy.*
import fs2.Stream
import io.circe.syntax.*

import java.util.UUID

/** What a legacy client told in the handshake. */
private[server] case class LegacySession(version: String, client: ClientInfo)

/** Serves a client of an earlier protocol version (2025-06-18, 2025-11-25) that starts with the `initialize` handshake.
  * The state of the connection (what the client told, the subscriptions) lives here, the server itself stays stateless.
  *
  * Input requests of the server are sent to the client as requests (`elicitation/create`) while the original request is
  * pending, and the original request is retried with the answers, like a client of 2026-07-28 would do.
  */
private[server] final class LegacyConnection[F[_]] private (
  core: ServerCore[F],
  supervisor: Supervisor[F],
  session: Ref[F, Option[LegacySession]],
  unsolicitedQueue: Queue[F, JsonRpc.Message],
  /** The requests of the server that wait for the answer of the client */
  pending: Ref[F, Map[JsonRpc.Id, Deferred[F, JsonRpc.Response]]],
  /** Signals for the requests that the client may cancel */
  cancelSignals: Ref[F, Map[JsonRpc.Id, List[Deferred[F, Unit]]]],
  /** Stops the updates of a subscribed resource */
  subscriptions: Ref[F, Map[String, F[Unit]]],
)(using F: Async[F]) extends JsonRpcHandler[F]:

  def isEstablished: F[Boolean] = session.get.map(_.isDefined)

  override def request(rpc: JsonRpc.Request, context: JsonRpcHandler.Context): Stream[F, JsonRpc.Message] =
    def failure(code: ErrorCode, message: String): Stream[F, JsonRpc.Message] =
      Stream.emit(JsonRpc.Response.Error(rpc.id, code, message, None))
    val id = rpc.id.fromJsonRpc

    LegacyCodec.decodeRequest(rpc) match
      case Left(DecodeError.UnknownMethod(method)) => failure(ErrorCode.MethodNotFound, s"Method not found: $method")
      case Left(DecodeError.InvalidParams(error))  =>
        failure(ErrorCode.InvalidParams, s"Invalid params: ${error.message}")
      case Right(init: Initialize) => initialize(id, init, context)
      case Right(_: Ping)          => Stream.emit(LegacyCodec.encodeEmptyResult(id))
      case Right(request)          =>
        Stream.eval(session.get).flatMap {
          case None          => failure(ErrorCode.InvalidRequest, "The connection is not initialized")
          case Some(session) =>
            request match
              case _: SetLevel  => Stream.emit(LegacyCodec.encodeEmptyResult(id))
              case r: Subscribe =>
                Stream.eval(subscribe(r.uri, session, context).map(_ =>
                  LegacyCodec.encodeEmptyResult(id): JsonRpc.Message
                ))
                  .handleErrorWith(e => Stream.eval(errorResponse(rpc.id, e)))
              case r: Unsubscribe =>
                Stream.eval(unsubscribe(r.uri).as(LegacyCodec.encodeEmptyResult(id): JsonRpc.Message))
              case standard: ClientRequest => execution(rpc.id, standard, session, context)
              case _: Initialize | _: Ping => failure(ErrorCode.InternalError, "Unexpected request")
        }
  end request

  override def notification(notification: JsonRpc.Notification, context: JsonRpcHandler.Context): F[Unit] =
    LegacyCodec.decodeNotification(notification) match
      case Right(LegacyNotification.Cancelled(cancelled)) =>
        // clients that share a session may use the same id, a cancellation of an ambiguous id is ignored
        cancelSignals.get.flatMap(_.get(cancelled.requestId.toJsonRpc) match
          case Some(single :: Nil) => single.complete(()).void
          case _                   => F.unit)
      case _ => F.unit

  override def response(response: JsonRpc.Response, context: JsonRpcHandler.Context): F[Unit] =
    pending.get.flatMap(_.get(response.id).traverse_(_.complete(response).void))

  override def unsolicited: Stream[F, JsonRpc.Message] = Stream.fromQueueUnterminated(unsolicitedQueue)

  /** Cancellations are handled by the connection (they also have to work over http). */
  override def cancelledRequest(notification: JsonRpc.Notification): Option[JsonRpc.Id] = None

  private def initialize(
    id: RequestId,
    init: Initialize,
    context: JsonRpcHandler.Context,
  ): Stream[F, JsonRpc.Message] =
    Stream.eval(Queue.unbounded[F, Option[JsonRpc.Message]]).flatMap { queue =>
      val version = LegacyVersion.negotiate(init.protocolVersion)
      val client = ClientInfo(Some(init.clientInfo), init.capabilities)
      val env = RequestEnv[F](
        client,
        context.authentication,
        context.connection,
        notification => queue.offer(Some(McpCodec.encodeNotification(notification))),
      )
      val run =
        (for
          before <- session.getAndSet(Some(LegacySession(version, client)))
          _ <- if before.isEmpty then startListChangeNotifications else F.unit
          instructions <- core.server.instructions(core.context(init._meta, env))
        yield LegacyCodec.encodeInitializeResult(
          id,
          InitializeResult(version, core.capabilities, core.server.serverInfo, instructions),
        ): JsonRpc.Message)
          .handleErrorWith(errorResponse(id.toJsonRpc, _))
          .flatMap(message => queue.offer(Some(message)))
          .guarantee(queue.offer(None))
      Stream.fromQueueNoneTerminated(queue).concurrently(Stream.eval(run))
    }

  private def startListChangeNotifications: F[Unit] =
    val all = SubscriptionFilter(Some(true), Some(true), Some(true))
    supervisor.supervise(
      core.changes(all)._2
        .evalMap(n => unsolicitedQueue.offer(McpCodec.encodeNotification(n)))
        .compile.drain
        .handleErrorWith(core.logError)
    ).void

  private def subscribe(uri: String, session: LegacySession, context: JsonRpcHandler.Context): F[Unit] =
    val env = RequestEnv[F](session.client, context.authentication, context.connection, _ => F.unit)
    core.resourceUpdates(uri, core.context(Meta.empty, env)) match
      case None =>
        McpError.raise[F](ErrorCode.MethodNotFound, "Resource subscriptions are not supported by this server").void
      case Some(updates) =>
        for
          fiber <- supervisor.supervise(
            updates.evalMap(n => unsolicitedQueue.offer(McpCodec.encodeNotification(n)))
              .compile.drain.handleErrorWith(core.logError)
          )
          previous <- subscriptions.getAndUpdate(_.updated(uri, fiber.cancel))
          _ <- previous.get(uri).sequence_
        yield ()

  private def unsubscribe(uri: String): F[Unit] =
    subscriptions.getAndUpdate(_ - uri).flatMap(_.get(uri).sequence_)

  /** Runs the request in the background, its messages (progress, requests to the client) are sent before the response.
    * The request ends when the client cancels it.
    */
  private def execution(
    id: JsonRpc.Id,
    request: ClientRequest,
    session: LegacySession,
    context: JsonRpcHandler.Context,
  ): Stream[F, JsonRpc.Message] =
    Stream.eval((Queue.unbounded[F, Option[JsonRpc.Message]], Deferred[F, Unit]).tupled).flatMap { (queue, cancelled) =>
      val env = RequestEnv[F](
        session.client,
        context.authentication,
        context.connection,
        notification => queue.offer(Some(McpCodec.encodeNotification(notification))),
      )
      val run = resolve(request, env, queue, round = 0)
        .map(response => LegacyCodec.encodeResponse(session.version, id.fromJsonRpc, response): JsonRpc.Message)
        .handleErrorWith(errorResponse(id, _))
        .flatMap(message => queue.offer(Some(message)))
        .guarantee(queue.offer(None))
      Stream.bracket(registerCancel(id, cancelled))(_ => unregisterCancel(id, cancelled)) >>
        Stream.fromQueueNoneTerminated(queue)
          .concurrently(Stream.eval(run))
          .interruptWhen(cancelled.get.attempt)
    }

  private def registerCancel(id: JsonRpc.Id, signal: Deferred[F, Unit]): F[Unit] =
    cancelSignals.update(_.updatedWith(id)(signals => Some(signal :: signals.getOrElse(Nil))))

  private def unregisterCancel(id: JsonRpc.Id, signal: Deferred[F, Unit]): F[Unit] =
    cancelSignals.update(_.updatedWith(id)(_.map(_.filterNot(_ eq signal)).filter(_.nonEmpty)))

  /** Executes the request, as long as the server needs input the client is asked and the request is retried. */
  private def resolve(
    request: ClientRequest,
    env: RequestEnv[F],
    queue: Queue[F, Option[JsonRpc.Message]],
    round: Int,
  ): F[ServerResponse] =
    core.execute(request, env).flatMap {
      case InputRequiredResult(requests, state, _) =>
        if round >= core.config.maxLegacyInputRounds then
          McpError.raise[F](ErrorCode.InternalError, "The request needed too many rounds of input").widen
        else
          for
            answers <- requests.getOrElse(Map.empty).toList.traverse((key, input) =>
              ask(input, queue).map(answer => key -> answer)
            )
            response <- resolve(withInput(request, answers.toMap, state), env, queue, round + 1)
          yield response
      case other => other.pure[F]
    }

  private def withInput(request: ClientRequest, responses: InputResponses, state: Option[String]): ClientRequest =
    request match
      case r: Tool.CallTool          => r.copy(inputResponses = Some(responses), requestState = state)
      case r: Prompts.GetPrompt      => r.copy(inputResponses = Some(responses), requestState = state)
      case r: Resources.ReadResource => r.copy(inputResponses = Some(responses), requestState = state)
      case other                     => other

  /** Sends the request to the client and waits for the answer. */
  private def ask(input: InputRequest, queue: Queue[F, Option[JsonRpc.Message]]): F[io.circe.Json] = input match
    case InputRequest.Elicit(params) =>
      for
        elicitationId <- F.delay(UUID.randomUUID().toString)
        requestId = RequestId.IdString(s"server-$elicitationId")
        rpcId = requestId.toJsonRpc
        answer <- Deferred[F, JsonRpc.Response]
        _ <- pending.update(_ + (rpcId -> answer))
        _ <- queue.offer(Some(LegacyCodec.encodeElicitation(requestId, params, elicitationId)))
        response <- answer.get.guarantee(pending.update(_ - rpcId))
      yield LegacyCodec.decodeElicitResult(response).asJson

  private def errorResponse(id: JsonRpc.Id, error: Throwable): F[JsonRpc.Message] =
    Errors.protocolError(id, error, legacy = true) match
      case Some(response) => response.pure[F].widen
      case None           => core.logError(error).as(Errors.internal(id))
end LegacyConnection

private[server] object LegacyConnection:
  def resource[F[_]](core: ServerCore[F])(using F: Async[F]): Resource[F, LegacyConnection[F]] =
    for
      supervisor <- Supervisor[F](await = false)
      connection <- Resource.eval(
        (
          Ref.of[F, Option[LegacySession]](None),
          Queue.unbounded[F, JsonRpc.Message],
          Ref.of[F, Map[JsonRpc.Id, Deferred[F, JsonRpc.Response]]](Map.empty),
          Ref.of[F, Map[JsonRpc.Id, List[Deferred[F, Unit]]]](Map.empty),
          Ref.of[F, Map[String, F[Unit]]](Map.empty),
        ).mapN(new LegacyConnection[F](core, supervisor, _, _, _, _, _))
      )
    yield connection
