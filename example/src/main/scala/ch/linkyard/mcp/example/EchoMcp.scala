package ch.linkyard.mcp.example

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.jsonrpc2.JsonRpcServer
import ch.linkyard.mcp.jsonrpc2.transport.StdioJsonRpcConnection
import ch.linkyard.mcp.protocol.Completion
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.Cursor
import ch.linkyard.mcp.protocol.Elicitation.Action
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import ch.linkyard.mcp.protocol.LoggingLevel
import ch.linkyard.mcp.protocol.Prompt
import ch.linkyard.mcp.protocol.PromptArgument
import ch.linkyard.mcp.protocol.PromptMessage
import ch.linkyard.mcp.protocol.Prompts
import ch.linkyard.mcp.protocol.Resource as Res
import ch.linkyard.mcp.protocol.Resources.ReadResource
import ch.linkyard.mcp.protocol.Role
import ch.linkyard.mcp.protocol.Sampling.Message
import ch.linkyard.mcp.server.CallContext
import ch.linkyard.mcp.server.LowlevelMcpServer
import ch.linkyard.mcp.server.McpError
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.McpServer.Client
import ch.linkyard.mcp.server.McpServer.ElicitationField
import ch.linkyard.mcp.server.McpServer.Pageable
import ch.linkyard.mcp.server.PromptFunction
import ch.linkyard.mcp.server.ToolFunction
import ch.linkyard.mcp.server.ToolFunction.Effect
import com.melvinlow.json.schema.generic.auto.given
import io.circe.generic.auto.given

import scala.concurrent.duration.DurationInt

object EchoMcp extends IOApp:
  private class Server extends McpServer[IO]:
    override def connect(client: Client[IO]): Resource[IO, McpServer.Session[IO]] =
      Resource.pure(Session(client))

  private class Session(client: Client[IO]) extends McpServer.Session[IO] with McpServer.ToolProvider[IO]
      with McpServer.PromptProvider[IO] with McpServer.ResourceProvider[IO]:
    override val serverInfo: PartyInfo = PartyInfo(
      "Echo MCP",
      "1.0.0",
    )
    override val maxPageSize: Int = 5
    override def instructions: IO[Option[String]] = None.pure
    override val tools: IO[List[ToolFunction[IO]]] = List(parrotTool, adderTool, userEmailTool(client)).pure
    override val prompts: IO[List[PromptFunction[IO]]] = List(storyPrompt).pure

    override def promptCompletions(
      promptName: String,
      argumentName: String,
      valueToComplete: String,
      otherArguments: Map[String, String],
      context: CallContext[IO],
    ): IO[Completion] = promptName match
      case "story" => argumentName match
          case "name" => Completion(values = Nil).pure
          case "color" =>
            val matching =
              if valueToComplete.isEmpty then colors else colors.filter(_.startsWith(valueToComplete.toLowerCase))
            Completion(matching).pure
      case _ => McpError.raise(ErrorCode.InvalidParams, s"Prompt $promptName not found")
    end promptCompletions

    override def resource(uri: String, context: CallContext[IO]): IO[ReadResource.Response] =
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

    override def resources(after: Option[Cursor]): fs2.Stream[IO, Pageable[Res]] =
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

    override def resourceTemplates(after: Option[Cursor]): fs2.Stream[IO, Pageable[Res.Template]] =
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

    override def resourceTemplateCompletions(
      uri: String,
      argumentName: String,
      valueToComplete: String,
      otherArguments: Map[String, String],
      context: CallContext[IO],
    ): IO[Completion] = (uri, argumentName) match
      case ("animal://{name}", "name") =>
        val options = AnimalBox.animals.map(_.name)
        val matching =
          if valueToComplete.isEmpty then options
          else options.filter(_.toLowerCase.startsWith(valueToComplete.toLowerCase))
        Completion(matching).pure
      case _ =>
        Completion(Nil).pure
  end Session

  case class TextInput(text: String)
  private def parrotTool: ToolFunction[IO] = ToolFunction.text(
    ToolFunction.Info(
      "parrot",
      "Parrot".some,
      "Acts like a parrot by saying everything back".some,
      Effect.ReadOnly,
      isOpenWorld = false,
    ),
    (in: TextInput, _) => IO(in.text + " gnarr"),
  )

  case class AdderInput(a: Int, b: Int)
  case class AdderOutput(result: Int)
  private def adderTool: ToolFunction[IO] = ToolFunction.structured(
    ToolFunction.Info(
      "adder",
      "Adder".some,
      "Adds two numbers".some,
      Effect.ReadOnly,
      isOpenWorld = false,
    ),
    (in: AdderInput, context) =>
      context.log(
        LoggingLevel.Info,
        "Will perform addition now, this will take some time",
      ) >> IO.sleep(1.second) >> context.reportProgress(0.5d, 1d.some) >> IO(AdderOutput(in.a + in.b)),
  )

  case class UserEmailInput(name: String)
  private def userEmailTool(client: McpServer.Client[IO]): ToolFunction[IO] = ToolFunction.text(
    ToolFunction.Info(
      "userEmail",
      "Get email for a user".some,
      "Finds the email address for a user when you have the name of the user".some,
      Effect.ReadOnly,
      isOpenWorld = true,
    ),
    (in: UserEmailInput, context) =>
      for
        _ <- context.reportProgress(0, 2d.some)
        compResp <- client.elicit(
          s"Where does ${in.name} work?",
          ElicitationField.Text("company", true, description = "The name of the company".some),
        )
        company <- (compResp.action match
          case Action.Accept  => compResp.content.flatMap(_("company")).flatMap(_.asString)
          case Action.Decline => None
          case Action.Cancel  => None
        ).toRight(McpError.error(ErrorCode.Other(-1), "User did not provide input")).liftTo[IO]
        _ <- context.reportProgress(1, 2d.some)
        sampleResp <- client.sample(
          List(Message(
            Role.User,
            Content.Text(
              s"What is the possible email for the user `${in.name}` that works for a company named `${company}`. " +
                s"Respond with just the most likely email address (like `peter@acme.com`) or with an empty response when you can't tell at all."
            ),
          )),
          100,
        )
        email <- (sampleResp.content match
          case content: Content.Text => content.text.some.filter(_.nonEmpty)
          case _                     => None
        ).toRight(McpError.error(ErrorCode.Other(-2), s"Could not determine the email domain for $company")).liftTo[IO]
        _ <- context.reportProgress(2, 2d.some)
      yield email,
  )

  private val storyPrompt = new PromptFunction[IO]:
    override val prompt: Prompt = Prompt(
      name = "story",
      title = "Tell me a Story".some,
      description = "Generates a story based on some parameters".some,
      arguments = List(
        PromptArgument(name = "name", title = "Your Name".some, None, true.some),
        PromptArgument(name = "color", title = "Favorite Color".some, None, true.some),
      ).some,
    )
    override def get(arguments: Map[String, String], callContext: CallContext[IO]): IO[Prompts.GetPrompt.Response] =
      Prompts.GetPrompt.Response(
        description = "Story Prompt".some,
        messages = PromptMessage(
          Role.User,
          Content.Text(
            s"Write a long story about ${arguments.get("name").getOrElse("a person")} which loves the color ${arguments.get("color").getOrElse("red")}."
          ),
        ) :: Nil,
      ).pure

  private val colors = List(
    "red",
    "blue",
    "green",
    "yellow",
    "orange",
    "purple",
    "pink",
    "brown",
    "black",
    "white",
    "gray",
    "cyan",
    "magenta",
    "lime",
    "teal",
  )

  private object AnimalBox:
    case class Animal(name: String, description: String):
      def uri: String = s"animal://$name"
    val animals: List[Animal] = List(
      Animal("Lion", "A powerful big cat known as the king of the jungle"),
      Animal("Elephant", "The largest land animal, famous for its memory and trunk"),
      Animal("Dolphin", "An intelligent aquatic mammal known for its playful nature"),
      Animal("Owl", "A nocturnal bird of prey with excellent night vision"),
      Animal("Penguin", "A flightless bird adapted to life in the water and cold climates"),
      Animal("Kangaroo", "A marsupial from Australia known for its jumping ability"),
      Animal("Giraffe", "The tallest land animal, recognized by its long neck"),
      Animal("Panda", "A bear native to China, famous for its love of bamboo"),
      Animal("Wolf", "A social canine known for living and hunting in packs"),
      Animal("Peacock", "A bird famous for its colorful and extravagant tail feathers"),
      Animal("Cheetah", "The fastest land animal, capable of incredible sprints"),
      Animal("Sloth", "A slow-moving mammal that spends most of its life hanging from trees"),
      Animal("Chameleon", "A lizard known for its ability to change color"),
      Animal("Octopus", "An intelligent sea creature with eight arms and problem-solving skills"),
      Animal("Hummingbird", "A tiny bird capable of hovering in place by rapidly flapping its wings"),
      Animal("Armadillo", "A small mammal with a protective armored shell"),
      Animal("Platypus", "A unique egg-laying mammal with a duck bill and beaver tail"),
      Animal("Rhinoceros", "A large, thick-skinned herbivore with one or two horns on its snout"),
      Animal("Red Fox", "A clever and adaptable mammal with a bushy tail"),
      Animal("Polar Bear", "A large bear native to the Arctic, excellent swimmer and hunter"),
    )

  override def run(args: List[String]): IO[ExitCode] =
    val sf = McpServer.create(new Server)
    IO(System.err.println("Welcome to Echo MCP")) >>
      LowlevelMcpServer.start(sf, e => IO(System.err.println(s"Error parsing: $e")))
        .flatMap(jsonRpc =>
          val conn = StdioJsonRpcConnection.resource[IO].map(StdioJsonRpcConnection.logRequestsToSyserr)
          JsonRpcServer.start(jsonRpc, conn)
        ).useForever.as(ExitCode.Success)
