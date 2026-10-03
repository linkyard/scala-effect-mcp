package ch.linkyard.mcp.server

import ch.linkyard.mcp.protocol.ElicitParams
import ch.linkyard.mcp.protocol.InputRequest
import ch.linkyard.mcp.protocol.InputRequests

/** The result of a request that may need more information from the user. */
enum Outcome[+A]:
  /** The request is done. */
  case Complete(value: A)

  /** The client is asked to answer the requests and to retry the request with the answers (and the `state`). Without a
    * `state` the handler only sees the answers on the retry.
    */
  case InputRequired(requests: InputRequests, state: Option[String] = None)

  /** Sets the state of an input request (does nothing for a completed outcome). */
  def withState(newState: String): Outcome[A] = this match
    case InputRequired(requests, _) => InputRequired(requests, Some(newState))
    case complete                   => complete

  def map[B](f: A => B): Outcome[B] = this match
    case Complete(value)                => Complete(f(value))
    case InputRequired(requests, state) => InputRequired(requests, state)

object Outcome:
  /** Asks the user for structured input (see [[ElicitationField]]). */
  def elicit(
    key: String,
    message: String,
    fields: ElicitationField*
  ): Outcome[Nothing] =
    InputRequired(Map(key -> InputRequest.Elicit(ElicitParams.Form(message, fields.toJsonSchema))))
