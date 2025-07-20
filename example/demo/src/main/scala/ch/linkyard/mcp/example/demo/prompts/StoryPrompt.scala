package ch.linkyard.mcp.example.demo.prompts

import cats.effect.IO
import cats.implicits.*
import ch.linkyard.mcp.protocol.Completion
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.Prompt
import ch.linkyard.mcp.protocol.PromptArgument
import ch.linkyard.mcp.protocol.PromptMessage
import ch.linkyard.mcp.protocol.Prompts
import ch.linkyard.mcp.protocol.Role
import ch.linkyard.mcp.server.CallContext
import ch.linkyard.mcp.server.PromptFunction

object StoryPrompt extends PromptFunction[IO]:
  override val prompt: Prompt = Prompt(
    name = "story",
    title = "Tell me a Story".some,
    description = "Generates a story based on some parameters".some,
    arguments = List(
      PromptArgument(name = "name", title = "Your Name".some, None, true.some),
      PromptArgument(name = "color", title = "Favorite Color".some, None, true.some),
    ).some,
  )
  override def get(arguments: Map[String, String], callContext: CallContext[IO]): IO[Prompts.GetPrompt.Response] =
    Prompts.GetPrompt.Response(
      description = "Story Prompt".some,
      messages = PromptMessage(
        Role.User,
        Content.Text(
          s"Write a long story about ${arguments.get("name").getOrElse("a person")} which loves the color ${arguments.get("color").getOrElse("red")}."
        ),
      ) :: Nil,
    ).pure

  def completions(
    argumentName: String,
    valueToComplete: String,
  ): IO[Completion] = argumentName match
    case "name" => Completion(values = Nil).pure
    case "color" =>
      val matching =
        if valueToComplete.isEmpty then colors
        else colors.filter(_.startsWith(valueToComplete.toLowerCase))
      Completion(matching).pure
    case _ => Completion(values = Nil).pure

  private val colors: List[String] = List(
    "red",
    "blue",
    "green",
    "yellow",
    "orange",
    "purple",
    "pink",
    "brown",
    "black",
    "white",
    "gray",
    "cyan",
    "magenta",
    "lime",
    "teal",
  )
