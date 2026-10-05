package ch.linkyard.mcp.server

import cats.MonadThrow
import cats.implicits.*
import ch.linkyard.mcp.protocol.ElicitParams
import ch.linkyard.mcp.protocol.ElicitResult
import ch.linkyard.mcp.protocol.InputRequest
import ch.linkyard.mcp.protocol.InputRequests
import io.circe.parser.decode
import io.circe.syntax.*

import scala.util.control.NoStackTrace

/** Asks the user for information while a tool runs, in sequential style:
  * {{{
  * for
  *   name <- ask.elicit("name", "Who are you?", ElicitationField.Text("name", required = true))
  *   ...
  * }}}
  *
  * A request that needs input is answered with an input required result and retried by the client with the answers. The
  * function therefore runs again from the start on every retry, the answers that are already known are returned
  * immediately and the first question without an answer ends the run. Side effects before a question are repeated, ask
  * first and act afterwards.
  */
trait Ask[F[_]]:
  /** Asks the user to fill in a form. */
  def elicit(key: String, message: String, fields: ElicitationField*): F[ElicitResult]

  /** Sends the user to a url (the client has to support it). */
  def elicitUrl(key: String, message: String, url: String): F[ElicitResult]

  /** Asks several questions in one round, the results are in the order of the questions. */
  def elicitAll(questions: Ask.Question*): F[List[ElicitResult]]

object Ask:
  /** A question for [[Ask.elicitAll]], the key identifies the answer. */
  enum Question:
    case Form(key: String, message: String, fields: List[ElicitationField])
    case Url(key: String, message: String, url: String)

    def key: String
    private[server] def toRequest: InputRequest = this match
      case Form(_, message, fields) => InputRequest.Elicit(ElicitParams.Form(message, fields.toJsonSchema))
      case Url(_, message, url)     => InputRequest.Elicit(ElicitParams.Url(message, url))

  /** Ends a run that needs answers (control flow, never visible to the user). */
  private[server] case class InputNeeded(requests: InputRequests) extends RuntimeException with NoStackTrace

  /** Runs the body with what the user answered so far. When the body needs more answers the outcome asks the client. */
  private[server] def run[F[_], A](context: RequestContext[F])(body: Ask[F] => F[A])(using
    F: MonadThrow[F]
  ): F[Outcome[A]] =
    val answers = knownAnswers(context)
    body(AskImpl[F](answers)).map(Outcome.Complete(_): Outcome[A]).recover {
      case InputNeeded(requests) => Outcome.InputRequired(requests, Some(answers.asJson.noSpaces))
    }

  /** The answers of the earlier rounds (kept in the state) and the answers of this retry. */
  private def knownAnswers[F[_]](context: RequestContext[F]): Map[String, ElicitResult] =
    val earlier = context.input.state.flatMap(decode[Map[String, ElicitResult]](_).toOption).getOrElse(Map.empty)
    val now = context.input.responses.keys.toList.flatMap(key => context.input.elicit(key).map(key -> _)).toMap
    earlier ++ now

  private class AskImpl[F[_]](answers: Map[String, ElicitResult])(using F: MonadThrow[F]) extends Ask[F]:
    override def elicit(key: String, message: String, fields: ElicitationField*): F[ElicitResult] =
      elicitAll(Question.Form(key, message, fields.toList)).map(_.head)

    override def elicitUrl(key: String, message: String, url: String): F[ElicitResult] =
      elicitAll(Question.Url(key, message, url)).map(_.head)

    override def elicitAll(questions: Question*): F[List[ElicitResult]] =
      val missing = questions.filterNot(q => answers.contains(q.key))
      if missing.isEmpty then questions.toList.map(q => answers(q.key)).pure[F]
      else F.raiseError(InputNeeded(missing.map(q => q.key -> q.toRequest).toMap))
