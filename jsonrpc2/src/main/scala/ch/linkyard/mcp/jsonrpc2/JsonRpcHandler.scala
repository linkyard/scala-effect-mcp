package ch.linkyard.mcp.jsonrpc2

import fs2.Stream

/** Handles the messages of a json rpc connection. */
trait JsonRpcHandler[F[_]]:
  /** The messages for a request: the messages related to it (notifications, requests to the client) followed by the
    * response. Cancelling the stream cancels the request, no further messages are expected after the response.
    */
  def request(request: JsonRpc.Request, context: JsonRpcHandler.Context): Stream[F, JsonRpc.Message]

  def notification(notification: JsonRpc.Notification, context: JsonRpcHandler.Context): F[Unit]

  /** A response of the client to a request of the server. */
  def response(response: JsonRpc.Response, context: JsonRpcHandler.Context): F[Unit]

  /** Messages that do not belong to a request (sent over the connection as they are produced). */
  def unsolicited: Stream[F, JsonRpc.Message]

  /** The id of the request that the client cancels with this notification. */
  def cancelledRequest(notification: JsonRpc.Notification): Option[JsonRpc.Id]
end JsonRpcHandler

object JsonRpcHandler:
  /** What is known about the message from the transport.
    *
    * @param headers
    *   the headers of the request (lower case name and raw value). `Authorization` is not included, it is
    *   [[Authentication]]. `None` for transports without headers.
    */
  case class Context(
    authentication: Authentication,
    connection: JsonRpcConnection.Info,
    headers: Option[Map[String, String]],
  )
