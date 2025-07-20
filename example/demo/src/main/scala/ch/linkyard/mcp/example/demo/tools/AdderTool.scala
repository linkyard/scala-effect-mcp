package ch.linkyard.mcp.example.demo.tools

import cats.effect.IO
import cats.implicits.*
import ch.linkyard.mcp.protocol.LoggingLevel
import ch.linkyard.mcp.server.ToolFunction
import com.melvinlow.json.schema.generic.auto.given
import io.circe.generic.auto.given

import scala.concurrent.duration.DurationInt

case class AdderInput(a: Int, b: Int)
case class AdderOutput(result: Int)

object AdderTool:
  def apply(): ToolFunction[IO] = ToolFunction.structured(
    ToolFunction.Info(
      "adder",
      "Adder".some,
      "Adds two numbers".some,
      ToolFunction.Effect.ReadOnly,
      isOpenWorld = false,
    ),
    (in: AdderInput, context) =>
      context.log(
        LoggingLevel.Info,
        "Will perform addition now, this will take some time",
      ) >> IO.sleep(1.second) >> context.reportProgress(0.5d, 1d.some) >> IO(AdderOutput(in.a + in.b)),
  )
