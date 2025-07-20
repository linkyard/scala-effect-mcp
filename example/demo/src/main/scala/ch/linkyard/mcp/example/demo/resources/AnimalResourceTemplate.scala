package ch.linkyard.mcp.example.demo.resources

import cats.effect.IO
import cats.implicits.*
import ch.linkyard.mcp.protocol.Completion
import ch.linkyard.mcp.protocol.Resource.Template
import ch.linkyard.mcp.server.CallContext
import ch.linkyard.mcp.server.ResourceTemplate

object AnimalResourceTemplate extends ResourceTemplate[IO]:
  override val template: Template = Template(
    uriTemplate = "animal://{name}",
    name = "Animals",
    title = "Animals in the Zoo".some,
    description = "Access all the animals in the zoo box".some,
    mimeType = "text/plain".some,
  )
  override def completions(
    argumentName: String,
    valueToComplete: String,
    otherArguments: Map[String, String],
    context: CallContext[IO],
  ): IO[Completion] =
    val options = AnimalBox.animals.map(_.name)
    val matching =
      if valueToComplete.isEmpty then options
      else options.filter(_.toLowerCase.startsWith(valueToComplete.toLowerCase))
    Completion(matching).pure
