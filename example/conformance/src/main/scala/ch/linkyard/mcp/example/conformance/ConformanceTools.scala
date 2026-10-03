package ch.linkyard.mcp.example.conformance

import cats.effect.IO
import cats.implicits.*
import ch.linkyard.mcp.protocol.ClientCapabilities
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.ElicitAction
import ch.linkyard.mcp.protocol.ElicitParams
import ch.linkyard.mcp.protocol.ElicitResult
import ch.linkyard.mcp.protocol.InputRequest
import ch.linkyard.mcp.protocol.JsonSchema
import ch.linkyard.mcp.protocol.McpErrorCode
import ch.linkyard.mcp.protocol.MissingRequiredClientCapabilityData
import ch.linkyard.mcp.protocol.ProgressToken
import ch.linkyard.mcp.protocol.Resource
import ch.linkyard.mcp.protocol.Tool.CallTool.Response
import ch.linkyard.mcp.server.ElicitationField
import ch.linkyard.mcp.server.McpError
import ch.linkyard.mcp.server.Outcome
import ch.linkyard.mcp.server.RequestContext
import ch.linkyard.mcp.server.ToolFunction
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

import scala.concurrent.duration.DurationInt

/** The tools of the reference fixture that the library can serve. Sampling, roots and logging are not supported by the
  * library, the tools that need them are left out.
  */
private[conformance] object ConformanceTools:
  private val noArguments: JsonSchema = JsonObject("type" -> "object".asJson, "properties" -> Json.obj())

  private def info(name: String, description: String): ToolFunction.Info =
    ToolFunction.Info(name, None, Some(description), ToolFunction.Effect.ReadOnly, isOpenWorld = false)

  private def tool(name: String, description: String, schema: JsonSchema = noArguments)(
    f: (JsonObject, RequestContext[IO]) => IO[Outcome[Response]]
  ): ToolFunction[IO] = ToolFunction.native[IO](info(name, description), schema, f)

  private def content(name: String, description: String)(contents: => List[Content]): ToolFunction[IO] =
    tool(name, description)((_, _) => IO.pure(Outcome.Complete(Response.Success(contents))))

  private def text(value: String): Outcome[Response] = Outcome.Complete(Response.Success(List(Content.Text(value))))

  /** The accepted answer to an elicitation. */
  private def accepted(context: RequestContext[IO], key: String): Option[JsonObject] =
    context.input.elicit(key).filter(_.action == ElicitAction.Accept).flatMap(_.content)

  private def acceptedText(context: RequestContext[IO], key: String, field: String): String =
    accepted(context, key).flatMap(_(field)).flatMap(_.asString).getOrElse("unknown")

  private val simpleText = content("test_simple_text", "Tests simple text content response")(
    List(Content.Text("This is a simple text response for testing."))
  )

  private val imageContent = content("test_image_content", "Tests image content response")(
    List(Content.Image(Fixtures.image, "image/png"))
  )

  private val audioContent = content("test_audio_content", "Tests audio content response")(
    List(Content.Audio(Fixtures.audio, "audio/wav"))
  )

  private val embeddedResource = content("test_embedded_resource", "Tests embedded resource content response")(
    List(Content.EmbeddedResource(
      Resource.Contents.Text("test://embedded-resource", Some("text/plain"), "This is an embedded resource content.")
    ))
  )

  private val multipleContentTypes = content(
    "test_multiple_content_types",
    "Tests response with multiple content types (text, image, resource)",
  )(
    List(
      Content.Text("Multiple content types test:"),
      Content.Image(Fixtures.image, "image/png"),
      Content.EmbeddedResource(Resource.Contents.Text(
        "test://mixed-content-resource",
        Some("application/json"),
        Json.obj("test" -> "data".asJson, "value" -> 123.asJson).noSpaces,
      )),
    )
  )

  private val progress = tool("test_tool_with_progress", "Tests tool that reports progress notifications") {
    (_, context) =>
      def step(done: Int) = context.reportProgress(done.toDouble, Some(100d), Some(s"Completed step $done of 100"))
      val token = context.meta.progressToken.fold("0") {
        case ProgressToken.TokenString(t) => t
        case ProgressToken.TokenNumber(t) => t.toString
      }
      step(0) >> IO.sleep(50.millis) >> step(50) >> IO.sleep(50.millis) >> step(100).as(text(token))
  }

  private val errorHandling = tool("test_error_handling", "Tests error response handling") { (_, _) =>
    IO.pure(Outcome.Complete(Response.Error(List(Content.Text("This tool intentionally returns an error for testing")))))
  }

  /** The schema has to be returned unchanged (SEP-1613 and SEP-2106). */
  private val jsonSchema2020 = tool(
    "json_schema_2020_12_tool",
    "Tool with JSON Schema 2020-12 features for conformance testing (SEP-1613)",
    Fixtures.JsonSchema2020,
  )((args, _) => IO.pure(text(s"JSON Schema 2020-12 tool called with: ${args.toJson.noSpaces}")))

  /** Requires a client that declared the sampling capability (the library cannot sample, the tool only checks). */
  private val missingCapability = tool("test_missing_capability", "Test tool requiring sampling") { (_, context) =>
    if context.client.capabilities.sampling.isEmpty then
      IO.raiseError(McpError.error(
        McpErrorCode.MissingRequiredClientCapability,
        "MissingRequiredClientCapabilityError",
        Some(MissingRequiredClientCapabilityData(ClientCapabilities(sampling = Some(JsonObject.empty))).asJson),
      ))
    else IO.pure(text("Success"))
  }

  private val nameField = ElicitationField.Text("name", required = true)
  private val confirmField = ElicitationField.YesNo("ok", required = true)

  private val elicitation = tool(
    "test_input_required_result_elicitation",
    "MRTR: returns InputRequiredResult with elicitation request",
  ) { (_, context) =>
    IO.pure(context.input.elicit("user_name") match
      case Some(_) => text(s"Hello, ${acceptedText(context, "user_name", "name")}!")
      case None    => Outcome.elicit("user_name", "What is your name?", nameField))
  }

  private val requestState = tool(
    "test_input_required_result_request_state",
    "MRTR: returns InputRequiredResult with requestState",
  ) { (_, context) =>
    val confirmed = accepted(context, "confirm").flatMap(_("ok")).flatMap(_.asBoolean).contains(true)
    IO.pure(
      if context.input.state.contains("request-state") && confirmed then text("state-ok: requestState validated")
      else Outcome.elicit("confirm", "Please confirm", confirmField).withState("request-state")
    )
  }

  /** The state is a plain value, the library signs it. */
  private val multiRound =
    tool("test_input_required_result_multi_round", "MRTR: multi-round InputRequiredResult workflow") { (_, context) =>
      IO.pure(context.input.state match
        case Some(state) if state.startsWith("round-2:") && context.input.elicit("step2").isDefined =>
          val color = acceptedText(context, "step2", "color")
          text(s"Multi-round complete for ${state.stripPrefix("round-2:")} who likes $color")
        case Some("round-1") if context.input.elicit("step1").isDefined =>
          val name = acceptedText(context, "step1", "name")
          Outcome.elicit("step2", "Step 2: What is your favorite color?", ElicitationField.Text("color", required = true))
            .withState(s"round-2:$name")
        case _ => Outcome.elicit("step1", "Step 1: What is your name?", nameField).withState("round-1"))
    }

  /** A state that the client changed is rejected by the library before this function runs. */
  private val tamperedState = tool(
    "test_input_required_result_tampered_state",
    "MRTR: HMAC-signed requestState integrity test",
  ) { (_, context) =>
    IO.pure(
      if context.input.state.contains("tamper-test") && context.input.elicit("confirm").isDefined then
        text("integrity-ok: state verified")
      else Outcome.elicit("confirm", "Please confirm", confirmField).withState("tamper-test")
    )
  }

  /** Only asks for what the client declared (the library can only ask for elicitation). */
  private val capabilities = tool(
    "test_input_required_result_capabilities",
    "MRTR: respects client capabilities in inputRequests",
  ) { (_, context) =>
    IO.pure(
      if context.input.responses.nonEmpty then
        text(s"capabilities-ok: received ${context.input.responses.keys.toList.sorted.mkString(",")}")
      else if context.client.capabilities.supportsFormElicitation then
        Outcome.elicit("elicit_input", "Elicitation input", ElicitationField.Text("value", required = true))
          .withState("capabilities-test")
      else text("No supported capabilities declared")
    )
  }

  /** An elicitation with a form that the helpers of the library cannot express (defaults, titled enums). */
  private def rawElicitation(name: String, description: String, schema: JsonObject, resultPrefix: String) =
    tool(name, description) { (_, context) =>
      IO.pure(context.input.elicit("form") match
        case Some(result) => text(s"$resultPrefix: ${describe(result)}")
        case None         => Outcome.InputRequired(
            Map("form" -> InputRequest.Elicit(ElicitParams.Form("Please review and update the form fields", schema)))
          ))
    }

  private def describe(result: ElicitResult): String =
    val action = result.action.toString.toLowerCase
    s"action=$action, content=${result.content.getOrElse(JsonObject.empty).toJson.noSpaces}"

  /** The tool of the elicitation tests of the earlier protocol versions (server initiated elicitation). */
  private val elicitationTool = tool(
    "test_elicitation",
    "Tests server-initiated elicitation (user input request)",
    JsonObject(
      "type" -> "object".asJson,
      "properties" -> Json.obj("message" -> Json.obj("type" -> "string".asJson)),
      "required" -> List("message").asJson,
    ),
  ) { (args, context) =>
    IO.pure(context.input.elicit("user_info") match
      case Some(result) => text(s"User response: ${describe(result)}")
      case None         =>
        val message = args("message").flatMap(_.asString).getOrElse("Please provide your information")
        Outcome.elicit(
          "user_info",
          message,
          ElicitationField.Text("username", required = true, description = Some("User's response")),
          ElicitationField.Text("email", required = true, description = Some("User's email address")),
        ))
  }

  /** `region` is mirrored into the header Mcp-Param-Region, the transport validates it. */
  private val headerParams = tool(
    "test_custom_header_params",
    "Tool with a parameter that is mirrored into a http header (SEP-2243)",
    Fixtures.HeaderParamsSchema,
  )((args, _) => IO.pure(text(s"Called with: ${args.toJson.noSpaces}")))

  private val streamingElicitation = tool(
    "test_streaming_elicitation",
    "Diagnostic tool validating response progress streams",
  )((_, context) => context.reportProgress(50d, Some(100d)).as(text("Streaming complete")))

  private def trigger(name: String, description: String, fire: IO[Unit]) =
    tool(name, description)((_, _) => fire.as(text("Mutation triggered")))

  /** The tools of the fixture, `fireToolChange` and `firePromptChange` notify the open subscriptions. */
  def all(fireToolChange: IO[Unit], firePromptChange: IO[Unit]): List[ToolFunction[IO]] = List(
    simpleText,
    imageContent,
    audioContent,
    embeddedResource,
    multipleContentTypes,
    progress,
    errorHandling,
    jsonSchema2020,
    missingCapability,
    elicitation,
    requestState,
    multiRound,
    tamperedState,
    capabilities,
    elicitationTool,
    rawElicitation(
      "test_elicitation_sep1034_defaults",
      "Tests elicitation with default values per SEP-1034",
      Fixtures.ElicitationDefaults,
      "Elicitation completed",
    ),
    rawElicitation(
      "test_elicitation_sep1330_enums",
      "Tests elicitation with enum schema improvements per SEP-1330",
      Fixtures.ElicitationEnums,
      "Elicitation completed",
    ),
    headerParams,
    streamingElicitation,
    trigger("test_trigger_tool_change", "Notifies the subscriptions that the tools changed", fireToolChange),
    trigger("test_trigger_prompt_change", "Notifies the subscriptions that the prompts changed", firePromptChange),
  )
end ConformanceTools
