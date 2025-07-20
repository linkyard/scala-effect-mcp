package ch.linkyard.mcp.example.demo.resources

import cats.effect.IO
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.protocol.Completion
import ch.linkyard.mcp.protocol.Cursor
import ch.linkyard.mcp.protocol.Resource as Res
import ch.linkyard.mcp.protocol.Resources.ReadResource
import ch.linkyard.mcp.server.McpError
import ch.linkyard.mcp.server.McpServer.Pageable

object AnimalResource:
  def resource(uri: String): IO[ReadResource.Response] =
    AnimalBox.animals.find(_.uri == uri) match
      case Some(animal) =>
        ReadResource.Response(contents =
          List(
            Res.Contents.Text(
              uri = uri,
              mimeType = "text/plain".some,
              text = animal.description,
            )
          )
        ).pure
      case None => McpError.raise(ErrorCode.InvalidParams, s"Resource $uri not found")

  def resources(after: Option[Cursor]): fs2.Stream[IO, Pageable[Res]] =
    fs2.Stream.emits(AnimalBox.animals
      .zipWithIndex.map((animal, index) =>
        (
          index.toString,
          Res(
            uri = animal.uri,
            name = animal.name,
            title = animal.name.some,
            description = None,
            mimeType = "text/plain".some,
            size = None,
          ),
        )
      )).drop(after.map(_.toInt + 1).getOrElse(0))

  def resourceTemplates: fs2.Stream[IO, Pageable[Res.Template]] =
    fs2.Stream.emit((
      "1",
      Res.Template(
        uriTemplate = "animal://{name}",
        name = "Animals",
        title = "Animals in the Zoo".some,
        description = "Access all the animals in the zoo box".some,
        mimeType = "text/plain".some,
      ),
    ))

  def resourceTemplateCompletions(
    uri: String,
    argumentName: String,
    valueToComplete: String,
  ): IO[Completion] = (uri, argumentName) match
    case ("animal://{name}", "name") =>
      val options = AnimalBox.animals.map(_.name)
      val matching =
        if valueToComplete.isEmpty then options
        else options.filter(_.toLowerCase.startsWith(valueToComplete.toLowerCase))
      Completion(matching).pure
    case _ =>
      Completion(Nil).pure
