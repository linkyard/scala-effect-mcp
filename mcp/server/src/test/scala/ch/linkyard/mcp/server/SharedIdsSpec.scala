package ch.linkyard.mcp.server

import cats.effect.IO
import cats.effect.Ref
import cats.effect.kernel.Deferred
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.server.McpServer.*
import ch.linkyard.mcp.server.TestSupport.*
import com.melvinlow.json.schema.generic.auto.given
import io.circe.Json
import io.circe.JsonObject
import io.circe.generic.auto.given
import io.circe.literal.*
import io.circe.syntax.*
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

/** Several clients may share one connection (a gateway that reuses a session) and use the same request ids. The answers
  * must still reach the request they belong to.
  */
class SharedIdsSpec extends AnyFunSpec with Matchers with OptionValues:
  private case class Text(text: String)

  private class GatedServer(gate: Deferred[IO, Unit], entered: Ref[IO, Int], bothEntered: Deferred[IO, Unit])
      extends McpServer[IO] with ToolProvider[IO]:
    override val serverInfo: Implementation = Implementation("gated", "1")
    override def instructions: IO[Option[String]] = IO.pure(None)
    private def info(name: String) =
      ToolFunction.Info(name, None, None, ToolFunction.Effect.ReadOnly, isOpenWorld = false)

    /** waits until the gate is opened and answers with its own text */
    private val gated = ToolFunction.text[IO, Text](
      info("gated"),
      (in, _) =>
        entered.updateAndGet(_ + 1).flatMap(n => IO.whenA(n == 2)(bothEntered.complete(()).void)) >>
          gate.get.as(s"answer for ${in.text}"),
    )
    private val echo = ToolFunction.text[IO, Text](info("echo"), (in, _) => IO.pure(s"answer for ${in.text}"))
    override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] = IO.pure(List(gated, echo))

  private case class Fixture(handler: JsonRpcHandler[IO], gate: Deferred[IO, Unit], bothEntered: Deferred[IO, Unit])

  private def withSession[A](legacy: Boolean)(test: Fixture => IO[A]): A =
    (for
      gate <- Deferred[IO, Unit]
      entered <- Ref.of[IO, Int](0)
      bothEntered <- Deferred[IO, Unit]
      factory = GatedServer(gate, entered, bothEntered).handlerFactory(McpServerConfig(), _ => IO.unit)
      info = JsonRpcConnection.Info.Http(None, None, Map.empty)
      result <-
        if legacy then
          factory.connection(info).use(handler =>
            messages(
              handler,
              JsonRpc.Request(
                JsonRpc.Id.IdInt(1),
                "initialize",
                Some(JsonObject(
                  "protocolVersion" -> "2025-06-18".asJson,
                  "capabilities" -> json"{}",
                  "clientInfo" -> json"""{"name": "shared gateway", "version": "1"}""",
                )),
              ),
            ) >> test(Fixture(handler, gate, bothEntered))
          )
        else test(Fixture(factory.stateless, gate, bothEntered))
    yield result).run

  /** The call of a tool, with the id that every client uses */
  private def call(legacy: Boolean, tool: String, text: String): JsonRpc.Request =
    val arguments = "arguments" -> json"""{"text": $text}"""
    if legacy then
      JsonRpc.Request(
        JsonRpc.Id.IdInt(7),
        "tools/call",
        Some(JsonObject("name" -> tool.asJson, arguments)),
      )
    else request(7, "tools/call", "name" -> tool.asJson, arguments)

  private def text(result: JsonObject): String =
    result("content").value.hcursor.downArray.get[String]("text").getOrElse("")

  for legacy <- List(true, false) do
    describe(s"Clients that share a connection and use the same request id (${
        if legacy then "legacy session" else "stateless"
      })") {
      it("should get their own answer") {
        val (waiting, quick) = withSession(legacy) { f =>
          for
            a <- messages(f.handler, call(legacy, "gated", "client A")).start
            _ <- IO.sleep(200.millis)
            quick <- messages(f.handler, call(legacy, "echo", "client B"))
            _ <- f.gate.complete(())
            waiting <- a.joinWithNever
          yield waiting -> quick
        }
        text(quick.result) shouldBe "answer for client B"
        text(waiting.result) shouldBe "answer for client A"
        waiting.size shouldBe 1
        quick.size shouldBe 1
      }

      it("should get their own answer when both are in flight at the same time") {
        val (a, b) = withSession(legacy) { f =>
          for
            fa <- messages(f.handler, call(legacy, "gated", "client A")).start
            fb <- messages(f.handler, call(legacy, "gated", "client B")).start
            _ <- f.bothEntered.get.timeout(5.seconds)
            _ <- f.gate.complete(())
            a <- fa.joinWithNever
            b <- fb.joinWithNever
          yield a -> b
        }
        text(a.result) shouldBe "answer for client A"
        text(b.result) shouldBe "answer for client B"
      }
    }

  describe("A cancellation of an id that two requests use") {
    it("should not cancel one of them by chance") {
      val (a, b) = withSession(legacy = true) { f =>
        for
          fa <- messages(f.handler, call(legacy = true, "gated", "client A")).start
          fb <- messages(f.handler, call(legacy = true, "gated", "client B")).start
          _ <- f.bothEntered.get.timeout(5.seconds)
          _ <- f.handler.notification(
            JsonRpc.Notification("notifications/cancelled", Some(JsonObject("requestId" -> 7.asJson))),
            context(),
          )
          _ <- IO.sleep(200.millis)
          _ <- f.gate.complete(())
          a <- fa.joinWithNever
          b <- fb.joinWithNever
        yield a -> b
      }
      text(a.result) shouldBe "answer for client A"
      text(b.result) shouldBe "answer for client B"
    }
  }
