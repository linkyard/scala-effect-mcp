package ch.linkyard.mcp.server

import ch.linkyard.mcp.protocol.Completion
import ch.linkyard.mcp.protocol.Resource

trait ResourceTemplate[F[_]]:
  val template: Resource.Template

  def completions(
    argumentName: String,
    valueToComplete: String,
    otherArguments: Map[String, String],
    context: CallContext[F],
  ): F[Completion]
