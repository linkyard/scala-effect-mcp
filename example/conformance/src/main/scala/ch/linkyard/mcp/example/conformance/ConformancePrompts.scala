package ch.linkyard.mcp.example.conformance

import cats.effect.IO
import ch.linkyard.mcp.protocol.Completion
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.ElicitAction
import ch.linkyard.mcp.protocol.Prompt
import ch.linkyard.mcp.protocol.PromptArgument
import ch.linkyard.mcp.protocol.PromptMessage
import ch.linkyard.mcp.protocol.Prompts.GetPrompt
import ch.linkyard.mcp.protocol.Resource
import ch.linkyard.mcp.protocol.Role
import ch.linkyard.mcp.server.ElicitationField
import ch.linkyard.mcp.server.Outcome
import ch.linkyard.mcp.server.PromptFunction
import ch.linkyard.mcp.server.RequestContext

private[conformance] object ConformancePrompts:
  private def prompt(
    name: String,
    title: String,
    description: String,
    arguments: List[PromptArgument] = Nil,
  )(
    f: (Map[String, String], RequestContext[IO]) => Outcome[GetPrompt.Response]
  ): PromptFunction[IO] =
    val definition = Prompt(name, Some(title), Some(description), Option.when(arguments.nonEmpty)(arguments))
    new PromptFunction[IO]:
      override val prompt: Prompt = definition
      override def get(arguments: Map[String, String], context: RequestContext[IO]): IO[Outcome[GetPrompt.Response]] =
        IO(f(arguments, context))
      override def argumentCompletions(
        argumentName: String,
        valueToComplete: String,
        otherArguments: Map[String, String],
        context: RequestContext[IO],
      ): IO[Completion] = IO.pure(Completion(Nil, Some(0), Some(false)))

  private def user(content: Content): PromptMessage = PromptMessage(Role.User, content)

  private def messages(messages: PromptMessage*): Outcome[GetPrompt.Response] =
    Outcome.Complete(GetPrompt.Response(messages.toList))

  private def argument(name: String, description: String) =
    PromptArgument(name, description = Some(description), required = Some(true))

  private val simple = prompt("test_simple_prompt", "Simple Test Prompt", "A simple prompt without arguments")((_, _) =>
    messages(user(Content.Text("This is a simple prompt for testing.")))
  )

  private val withArguments = prompt(
    "test_prompt_with_arguments",
    "Prompt With Arguments",
    "A prompt with required arguments",
    List(argument("arg1", "First test argument"), argument("arg2", "Second test argument")),
  )((args, _) =>
    messages(user(Content.Text(
      s"Prompt with arguments: arg1='${args.getOrElse("arg1", "")}', arg2='${args.getOrElse("arg2", "")}'"
    )))
  )

  private val withEmbeddedResource = prompt(
    "test_prompt_with_embedded_resource",
    "Prompt With Embedded Resource",
    "A prompt that includes an embedded resource",
    List(argument("resourceUri", "URI of the resource to embed")),
  )((args, _) =>
    messages(
      user(Content.EmbeddedResource(Resource.Contents.Text(
        args.getOrElse("resourceUri", ""),
        Some("text/plain"),
        "Embedded resource content for testing.",
      ))),
      user(Content.Text("Please process the embedded resource above.")),
    )
  )

  private val withImage = prompt("test_prompt_with_image", "Prompt With Image", "A prompt that includes image content")(
    (_, _) =>
      messages(
        user(Content.Image(Fixtures.image, "image/png")),
        user(Content.Text("Please analyze the image above.")),
      )
  )

  private val inputRequired = prompt(
    "test_input_required_result_prompt",
    "Input Required Prompt",
    "MRTR: prompt that requires elicitation input",
  ) { (_, context) =>
    context.input.elicit("user_context") match
      case None =>
        Outcome.elicit(
          "user_context",
          "What context should the prompt use?",
          ElicitationField.Text("context", required = true),
        )
      case Some(answer) =>
        val value = Some(answer).filter(_.action == ElicitAction.Accept).flatMap(_.content).flatMap(_("context"))
          .flatMap(_.asString).getOrElse("unknown")
        messages(user(Content.Text(s"Prompt with context: $value")))
  }

  val all: List[PromptFunction[IO]] = List(simple, withArguments, withEmbeddedResource, withImage, inputRequired)
end ConformancePrompts
