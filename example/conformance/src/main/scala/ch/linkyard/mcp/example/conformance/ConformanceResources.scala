package ch.linkyard.mcp.example.conformance

import cats.effect.IO
import ch.linkyard.mcp.protocol.CacheScope
import ch.linkyard.mcp.protocol.Completion
import ch.linkyard.mcp.protocol.Resource
import ch.linkyard.mcp.protocol.Resources.ReadResource
import ch.linkyard.mcp.server.McpError
import ch.linkyard.mcp.server.Outcome
import ch.linkyard.mcp.server.RequestContext
import ch.linkyard.mcp.server.ResourceTemplate
import io.circe.Json
import io.circe.syntax.*

import scala.concurrent.duration.DurationInt

private[conformance] object ConformanceResources:
  val resources: List[Resource] = List(
    Resource(
      Fixtures.StaticTextUri,
      "static-text",
      Some("Static Text Resource"),
      Some("A static text resource for testing"),
      Some("text/plain"),
    ),
    Resource(
      Fixtures.StaticBinaryUri,
      "static-binary",
      Some("Static Binary Resource"),
      Some("A static binary resource (image) for testing"),
      Some("image/png"),
    ),
    Resource(
      Fixtures.WatchedUri,
      "watched-resource",
      Some("Watched Resource"),
      Some("A resource that auto-updates every 3 seconds"),
      Some("text/plain"),
    ),
  )

  val template: ResourceTemplate[IO] = new ResourceTemplate[IO]:
    override val template: Resource.Template = Resource.Template(
      Fixtures.TemplateUri,
      "template",
      Some("Resource Template"),
      Some("A resource template with parameter substitution"),
      Some("application/json"),
    )
    override def completions(
      argumentName: String,
      valueToComplete: String,
      otherArguments: Map[String, String],
      context: RequestContext[IO],
    ): IO[Completion] = IO.pure(Completion(Nil, Some(0), Some(false)))

  def read(uri: String): IO[Outcome[ReadResource.Response]] =
    val contents = uri match
      case Fixtures.StaticTextUri =>
        Some(Resource.Contents.Text(uri, Some("text/plain"), "This is the content of the static text resource."))
      case Fixtures.StaticBinaryUri => Some(Resource.Contents.Blob(uri, Some("image/png"), Fixtures.ImageBase64))
      case Fixtures.WatchedUri      => Some(Resource.Contents.Text(uri, Some("text/plain"), "Watched resource content"))
      case _ if uri.startsWith(Fixtures.TemplatePrefix) && uri.endsWith("/data") =>
        val id = uri.stripPrefix(Fixtures.TemplatePrefix).stripSuffix("/data")
        Option.when(id.nonEmpty && !id.contains('/'))(Resource.Contents.Text(
          uri,
          Some("application/json"),
          Json.obj(
            "id" -> id.asJson,
            "templateTest" -> true.asJson,
            "data" -> s"Data for ID: $id".asJson,
          ).noSpaces,
        ))
      case _ => None
    contents match
      case Some(c) =>
        IO.pure(Outcome.Complete(ReadResource.Response(
          List(c),
          ttlMs = 5.minutes.toMillis,
          cacheScope = CacheScope.Private,
        )))
      case None => IO.raiseError(McpError.resourceNotFound(uri))
  end read
end ConformanceResources
