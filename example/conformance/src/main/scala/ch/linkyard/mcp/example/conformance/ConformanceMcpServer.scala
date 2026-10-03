package ch.linkyard.mcp.example.conformance

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import ch.linkyard.mcp.jsonrpc2.transport.http4s.McpServerRoute
import ch.linkyard.mcp.jsonrpc2.transport.http4s.SessionStore
import ch.linkyard.mcp.server.McpServer.*
import ch.linkyard.mcp.server.McpServerConfig
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.DurationInt

/** Serves the [[ConformanceServer]] on http://127.0.0.1:PORT/mcp, the port is the first argument or the environment
  * variable PORT (default 3000).
  */
object ConformanceMcpServer extends IOApp:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  override def run(args: List[String]): IO[ExitCode] =
    port(args).flatMap(p => program(p).useForever).as(ExitCode.Success)

  private def port(args: List[String]): IO[Port] =
    val requested = args.headOption.orElse(sys.env.get("PORT")).getOrElse("3000")
    IO.fromOption(Port.fromString(requested))(IllegalArgumentException(s"Invalid port: $requested"))

  private def program(port: Port): Resource[IO, Unit] =
    for
      given SessionStore[IO] <- SessionStore.inMemory[IO](30.minutes)
      server <- Resource.eval(ConformanceServer.create)
      factory = server.handlerFactory(McpServerConfig.default, logError)
      route = McpServerRoute.route(factory)
      _ <- EmberServerBuilder.default[IO]
        .withHost(Host.fromString("127.0.0.1").get)
        .withPort(port)
        .withHttpApp(route.orNotFound)
        .build
      _ <- Resource.eval(Logger[IO].info(s"Conformance server running on http://127.0.0.1:$port/mcp"))
    yield ()

  private def logError(error: Throwable): IO[Unit] =
    Logger[IO].warn(error)("Error while handling a request")
end ConformanceMcpServer
