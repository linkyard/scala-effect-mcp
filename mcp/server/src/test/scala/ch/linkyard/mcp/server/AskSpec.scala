package ch.linkyard.mcp.server

import cats.effect.IO
import cats.effect.Ref
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.server.McpServer.*
import ch.linkyard.mcp.server.TestSupport.*
import com.melvinlow.json.schema.generic.auto.given
import io.circe.Json
import io.circe.generic.auto.given
import io.circe.literal.*
import io.circe.syntax.*
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class AskSpec extends AnyFunSpec with Matchers with OptionValues with EitherValues:
  private case class Nothing(unused: Option[String] = None)
  private val toolInfo = ToolFunction.Info("interview", None, None, ToolFunction.Effect.ReadOnly, isOpenWorld = false)

  private class InterviewServer(runs: Ref[IO, Int]) extends McpServer[IO] with ToolProvider[IO]:
    override val serverInfo: Implementation = Implementation("interview", "1")
    override def instructions: IO[Option[String]] = IO.pure(None)

    private val sequential = ToolFunction.interactiveText[IO, Nothing](
      toolInfo.copy(name = "sequential"),
      (_, _, ask) =>
        for
          _ <- runs.update(_ + 1)
          name <- ask.elicit("name", "Name?", ElicitationField.Text("name", true))
          color <- ask.elicit("color", "Color?", ElicitationField.Choice("color", true, List("red", "blue")))
        yield s"${field(name, "name")} likes ${field(color, "color")}",
    )
    private val batched = ToolFunction.interactiveText[IO, Nothing](
      toolInfo.copy(name = "batched"),
      (_, _, ask) =>
        ask.elicitAll(
          Ask.Question.Form("a", "A?", List(ElicitationField.Text("a", true))),
          Ask.Question.Form("b", "B?", List(ElicitationField.Text("b", true))),
        ).map(_.map(field(_, "x")).mkString(",")),
    )
    private val url = ToolFunction.interactiveText[IO, Nothing](
      toolInfo.copy(name = "url"),
      (_, _, ask) => ask.elicitUrl("login", "Please log in", "https://example.com/login").map(_.action.toString),
    )
    private val structured = ToolFunction.interactiveStructured[IO, Nothing, Json2](
      toolInfo.copy(name = "structured"),
      (_, _, ask) => ask.elicit("n", "N?", ElicitationField.Number("n", true)).map(r => Json2(field(r, "n"))),
    )
    private val declined = ToolFunction.interactiveText[IO, Nothing](
      toolInfo.copy(name = "declined"),
      (_, _, ask) =>
        ask.elicit("name", "Name?", ElicitationField.Text("name", true)).map(r =>
          if r.action == ElicitAction.Accept then "ok" else "no"
        ),
    )
    override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] =
      IO.pure(List(sequential, batched, url, structured, declined))

  private case class Json2(value: String)

  private def field(result: ElicitResult, name: String): String =
    result.content.flatMap(
      _(name)
    ).flatMap(_.asString).orElse(result.content.flatMap(_(name)).map(_.noSpaces)).getOrElse("?")

  private def withHandler[A](test: (JsonRpcHandler[IO], Ref[IO, Int]) => IO[A]): A =
    (for
      runs <- Ref.of[IO, Int](0)
      handler =
        InterviewServer(runs).handlerFactory(McpServerConfig(supportLegacyClients = false), _ => IO.unit).stateless
      result <- test(handler, runs)
    yield result).run

  private def call(h: JsonRpcHandler[IO], id: Int, tool: String, extra: (String, Json)*): IO[List[JsonRpc.Message]] =
    messages(h, request(id, "tools/call", ("name" -> tool.asJson) +: extra*))

  private def accept(content: Json): Json = json"""{"action": "accept", "content": $content}"""

  describe("Interactive tools") {
    it("should ask one question after the other and keep the earlier answers") {
      val (first, second, third, runs) = withHandler((h, runs) =>
        for
          first <- call(h, 1, "sequential")
          second <- call(
            h,
            2,
            "sequential",
            "inputResponses" -> json"""{"name": ${accept(json"""{"name": "Ada"}""")}}""",
            "requestState" -> first.result("requestState").value,
          )
          third <- call(
            h,
            3,
            "sequential",
            "inputResponses" -> json"""{"color": ${accept(json"""{"color": "blue"}""")}}""",
            "requestState" -> second.result("requestState").value,
          )
          runs <- runs.get
        yield (first.result, second.result, third.result, runs)
      )
      first("resultType") shouldBe Some("input_required".asJson)
      first("inputRequests").value.asObject.value.keys.toList shouldBe List("name")
      second("inputRequests").value.asObject.value.keys.toList shouldBe List("color")
      third("resultType") shouldBe Some("complete".asJson)
      third("content") shouldBe Some(json"""[{"type": "text", "text": "Ada likes blue"}]""")
      runs shouldBe 3 // the function runs again for every round
    }

    it("should describe the form of the question") {
      val result = withHandler((h, _) => call(h, 1, "sequential")).result
      val params = result("inputRequests").value.hcursor.downField("name").downField("params")
      params.get[String]("message").value shouldBe "Name?"
      params.downField("requestedSchema").get[List[String]]("required").value shouldBe List("name")
      params.downField(
        "requestedSchema"
      ).downField("properties").downField("name").get[String]("type").value shouldBe "string"
    }

    it("should ask several questions in one round") {
      val (first, second) = withHandler((h, _) =>
        for
          first <- call(h, 1, "batched")
          second <- call(
            h,
            2,
            "batched",
            "inputResponses" -> json"""{"a": ${accept(json"""{"x": "1"}""")}, "b": ${accept(json"""{"x": "2"}""")}}""",
            "requestState" -> first.result("requestState").value,
          )
        yield first.result -> second.result
      )
      first("inputRequests").value.asObject.value.keys.toSet shouldBe Set("a", "b")
      second("content") shouldBe Some(json"""[{"type": "text", "text": "1,2"}]""")
    }

    it("should only ask the questions that are not answered yet") {
      val result = withHandler((h, _) =>
        for
          first <- call(h, 1, "batched")
          second <- call(
            h,
            2,
            "batched",
            "inputResponses" -> json"""{"a": ${accept(json"""{"x": "1"}""")}}""",
            "requestState" -> first.result("requestState").value,
          )
        yield second.result
      )
      result("inputRequests").value.asObject.value.keys.toList shouldBe List("b")
    }

    it("should ask to open a url") {
      val urlCapable = clientMeta(capabilities = json"""{"elicitation": {"url": {}}}""")
      val result =
        withHandler((h, _) => messages(h, requestWithMeta(1, "tools/call", urlCapable, "name" -> "url".asJson))).result
      val params = result("inputRequests").value.hcursor.downField("login").downField("params")
      params.get[String]("mode").value shouldBe "url"
      params.get[String]("url").value shouldBe "https://example.com/login"
    }

    it("should need the url capability of the client") {
      val rpc = requestWithMeta(1, "tools/call", clientMeta(), "name" -> "url".asJson)
      val error = withHandler((h, _) => messages(h, rpc)).error
      error.code shouldBe McpErrorCode.MissingRequiredClientCapability
    }

    it("should give the structured result") {
      val result = withHandler((h, _) =>
        for
          first <- call(h, 1, "structured")
          second <- call(
            h,
            2,
            "structured",
            "inputResponses" -> json"""{"n": ${accept(json"""{"n": 5}""")}}""",
            "requestState" -> first.result("requestState").value,
          )
        yield second.result
      )
      result("structuredContent") shouldBe Some(json"""{"value": "5"}""")
    }

    it("should hand the answer to the function when the user declined") {
      val result = withHandler((h, _) =>
        for
          first <- call(h, 1, "declined")
          second <- call(
            h,
            2,
            "declined",
            "inputResponses" -> json"""{"name": {"action": "decline"}}""",
            "requestState" -> first.result("requestState").value,
          )
        yield second.result
      )
      result("content") shouldBe Some(json"""[{"type": "text", "text": "no"}]""")
    }

    it("should ignore answers that are not valid") {
      val result = withHandler((h, _) =>
        for
          first <- call(h, 1, "declined")
          second <- call(
            h,
            2,
            "declined",
            "inputResponses" -> json"""{"name": {"unexpected": true}}""",
            "requestState" -> first.result("requestState").value,
          )
        yield second.result
      )
      result("resultType") shouldBe Some("input_required".asJson)
    }
  }
