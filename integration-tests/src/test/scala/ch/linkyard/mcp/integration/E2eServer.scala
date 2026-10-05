package ch.linkyard.mcp.integration

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.implicits.*
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.ElicitAction
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.protocol.Tool
import ch.linkyard.mcp.server.Ask
import ch.linkyard.mcp.server.ElicitationField
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.McpServer.*
import ch.linkyard.mcp.server.Outcome
import ch.linkyard.mcp.server.RequestContext
import ch.linkyard.mcp.server.ToolFunction
import com.melvinlow.json.schema.generic.auto.given
import fs2.concurrent.Topic
import io.circe.Json
import io.circe.JsonObject
import io.circe.generic.auto.given
import io.circe.syntax.*

/** The server that is used to test the transports. */
class E2eServer(
  val changes: Topic[IO, Unit],
  val slowStarted: Deferred[IO, Unit],
  val slowCancelled: Deferred[IO, Unit],
  val gate: Deferred[IO, Unit],
  val bothEntered: Deferred[IO, Unit],
  entered: Ref[IO, Int],
) extends McpServer[IO] with ToolProvider[IO] with ToolProviderWithChanges[IO]:
  override val serverInfo: Implementation = Implementation("e2e", "1.0")
  override def instructions(@scala.annotation.unused context: RequestContext[IO]): IO[Option[String]] =
    IO.pure(Some("test server"))
  override def toolChanges: fs2.Stream[IO, Unit] = changes.subscribe(10)

  private def info(name: String) = ToolFunction.Info(name, None, None, ToolFunction.Effect.ReadOnly, isOpenWorld = false)
  private val echo = ToolFunction.text[IO, E2eServer.Text](info("echo"), (in, _) => IO.pure(in.text))
  private val progress = ToolFunction.text[IO, E2eServer.Text](
    info("progress"),
    (in, ctx) => ctx.reportProgress(1, Some(2)) >> ctx.reportProgress(2, Some(2)).as(in.text),
  )
  private val slow = ToolFunction.text[IO, E2eServer.Text](
    info("slow"),
    (_, _) => slowStarted.complete(()) >> IO.never[String].onCancel(slowCancelled.complete(()).void),
  )
  private val ask = ToolFunction.interactiveText[IO, E2eServer.Text](
    info("ask"),
    (in, _, ask: Ask[IO]) =>
      ask.elicit("name", s"Name for ${in.text}?", ElicitationField.Text("name", true)).map(answer =>
        if answer.action == ElicitAction.Accept then
          s"hello ${answer.content.flatMap(_("name")).flatMap(_.asString).getOrElse("?")}"
        else "nobody"
      ),
  )

  /** The parameter `region` is mirrored into the header `Mcp-Param-Region`. */
  /** waits until the gate is opened, answers with the text of its own request */
  private val gated = ToolFunction.text[IO, E2eServer.Text](
    info("gated"),
    (in, _) =>
      entered.updateAndGet(_ + 1).flatMap(n => IO.whenA(n == 2)(bothEntered.complete(()).void)) >>
        gate.get.as(s"answer for ${in.text}"),
  )
  private val regional = ToolFunction.native[IO](
    info("regional"),
    JsonObject(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "region" -> Json.obj("type" -> "string".asJson, "x-mcp-header" -> "Region".asJson)
      ),
    ),
    (args, _) =>
      IO.pure(Outcome.Complete(Tool.CallTool.Response.Success(
        List(Content.Text(args("region").flatMap(_.asString).getOrElse("-")))
      ))),
  )
  override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] =
    IO.pure(List(echo, progress, slow, ask, regional, gated))

object E2eServer:
  case class Text(text: String)

  def create: IO[E2eServer] =
    (
      Topic[IO, Unit],
      Deferred[IO, Unit],
      Deferred[IO, Unit],
      Deferred[IO, Unit],
      Deferred[IO, Unit],
      Ref.of[IO, Int](0),
    ).mapN(E2eServer(_, _, _, _, _, _))
