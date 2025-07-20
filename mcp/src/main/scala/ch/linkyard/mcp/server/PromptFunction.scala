package ch.linkyard.mcp.server

import ch.linkyard.mcp.protocol.Prompt
import ch.linkyard.mcp.protocol.Prompts

trait PromptFunction[F[_]]:
  val prompt: Prompt
  def get(arguments: Map[String, String], callContext: CallContext[F]): F[Prompts.GetPrompt.Response]
