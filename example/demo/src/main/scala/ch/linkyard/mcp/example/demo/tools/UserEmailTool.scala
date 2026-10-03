package ch.linkyard.mcp.example.demo.tools

import cats.effect.IO
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.protocol.ElicitAction
import ch.linkyard.mcp.server.McpError
import ch.linkyard.mcp.server.RequestContext
import ch.linkyard.mcp.server.ElicitationField
import ch.linkyard.mcp.server.Ask
import ch.linkyard.mcp.server.ToolFunction
import com.melvinlow.json.schema.generic.auto.given
import io.circe.generic.auto.given

/** Asks the user for the company name and then guesses the email address of the user.
  *
  * This is a simple example of how a tool can ask the user for additional information (elicitation). The function runs
  * again after the user answered: the answer is returned by `ask.elicit` and the first question without an answer ends
  * the run, so everything before the question has to be repeatable.
  */
object UserEmailTool:
  case class Input(name: String)

  def apply(): ToolFunction[IO] = ToolFunction.interactiveText(
    ToolFunction.Info(
      "userEmail",
      "Get email for a user".some,
      "Guesses the email address of a user when you have the name of the user".some,
      ToolFunction.Effect.ReadOnly,
      isOpenWorld = true,
    ),
    execute,
  )

  private def execute(in: Input, context: RequestContext[IO], ask: Ask[IO]): IO[String] =
    for
      answer <- ask.elicit(
        "company",
        s"Where does ${in.name} work?",
        ElicitationField.Text("company", true, description = "The name of the company".some),
      )
      company <- (answer.action match
        case ElicitAction.Accept => answer.content.flatMap(_("company")).flatMap(_.asString)
        case _                   => None
      ).toRight(McpError.error(ErrorCode.Other(-1), "User did not provide input")).liftTo[IO]
    yield s"${in.name.toLowerCase.replace(' ', '.')}@${company.toLowerCase.replace(' ', '-')}.com"
