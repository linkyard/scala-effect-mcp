package ch.linkyard.mcp.integration

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.implicits.*
import ch.linkyard.mcp.protocol.ElicitAction
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.server.Ask
import ch.linkyard.mcp.server.ElicitationField
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.McpServer.*
import ch.linkyard.mcp.server.RequestContext
import ch.linkyard.mcp.server.ToolFunction
import com.melvinlow.json.schema.generic.auto.given
import fs2.concurrent.Topic
import io.circe.generic.auto.given

/** The server that is used to test the transports. */
class E2eServer(
  val changes: Topic[IO, Unit],
  val slowStarted: Deferred[IO, Unit],
  val slowCancelled: Deferred[IO, Unit],
) extends McpServer[IO] with ToolProvider[IO] with ToolProviderWithChanges[IO]:
  override val serverInfo: Implementation = Implementation("e2e", "1.0")
  override def instructions: IO[Option[String]] = IO.pure(Some("test server"))
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
  override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] = IO.pure(List(echo, progress, slow, ask))

object E2eServer:
  case class Text(text: String)

  def create: IO[E2eServer] =
    (Topic[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit]).mapN(E2eServer(_, _, _))
