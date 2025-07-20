package ch.linkyard.mcp.example.demo.tools

import cats.effect.IO
import cats.implicits.*
import ch.linkyard.mcp.server.ToolFunction
import com.melvinlow.json.schema.generic.auto.given
import io.circe.generic.auto.given

case class TextInput(text: String)

object ParrotTool:
  def apply(): ToolFunction[IO] = ToolFunction.text(
    ToolFunction.Info(
      "parrot",
      "Parrot".some,
      "Acts like a parrot by saying everything back".some,
      ToolFunction.Effect.ReadOnly,
      isOpenWorld = false,
    ),
    (in: TextInput, _) => IO(in.text + " gnarr"),
  )
