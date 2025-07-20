package ch.linkyard.mcp.server

import ch.linkyard.mcp.protocol.Completion
import ch.linkyard.mcp.protocol.Prompt
import ch.linkyard.mcp.protocol.Prompts

trait PromptFunction[F[_]]:
  val prompt: Prompt
  def get(arguments: Map[String, String], callContext: CallContext[F]): F[Prompts.GetPrompt.Response]
  def argumentCompletions(
    argumentName: String,
    valueToComplete: String,
    otherArguments: Map[String, String],
    context: CallContext[F],
  ): F[Completion]
