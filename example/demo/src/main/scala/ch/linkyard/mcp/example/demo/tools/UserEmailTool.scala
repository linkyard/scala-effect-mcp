package ch.linkyard.mcp.example.demo.tools

import cats.effect.IO
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.Elicitation.Action
import ch.linkyard.mcp.protocol.Role
import ch.linkyard.mcp.protocol.Sampling.Message
import ch.linkyard.mcp.server.CallContext
import ch.linkyard.mcp.server.McpError
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.ToolFunction
import com.melvinlow.json.schema.generic.auto.given
import io.circe.generic.auto.given

/** Uses elicitation to get the company name and then samples a possible email address for the user.
  *
  * This is a simple example of how to use the elicitation and sampling features of the MCP protocol.
  */
object UserEmailTool:
  case class Input(name: String)

  def apply(client: McpServer.Client[IO]): ToolFunction[IO] = ToolFunction.text(
    ToolFunction.Info(
      "userEmail",
      "Get email for a user".some,
      "Finds the email address for a user when you have the name of the user".some,
      ToolFunction.Effect.ReadOnly,
      isOpenWorld = true,
    ),
    execute(client),
  )

  private def execute(client: McpServer.Client[IO])(in: Input, context: CallContext[IO]): IO[String] =
    for
      _ <- context.reportProgress(0, 2d.some)
      compResp <- client.elicit(
        s"Where does ${in.name} work?",
        McpServer.ElicitationField.Text("company", true, description = "The name of the company".some),
      )
      company <- (compResp.action match
        case Action.Accept  => compResp.content.flatMap(_("company")).flatMap(_.asString)
        case Action.Decline => None
        case Action.Cancel  => None
      ).toRight(McpError.error(ErrorCode.Other(-1), "User did not provide input")).liftTo[IO]
      _ <- context.reportProgress(1, 2d.some)
      sampleResp <- client.sample(
        List(Message(
          Role.User,
          Content.Text(
            s"What is the possible email for the user `${in.name}` that works for a company named `${company}`. " +
              s"Respond with just the most likely email address (like `peter@acme.com`) or with an empty response when you can't tell at all."
          ),
        )),
        100,
      )
      email <- (sampleResp.content match
        case content: Content.Text => content.text.some.filter(_.nonEmpty)
        case _                     => None
      ).toRight(McpError.error(ErrorCode.Other(-2), s"Could not determine the email domain for $company")).liftTo[IO]
      _ <- context.reportProgress(2, 2d.some)
    yield email
