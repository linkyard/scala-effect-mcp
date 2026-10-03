package ch.linkyard.mcp.example.demo

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
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.DurationInt

object HttpDemoMcpServer extends IOApp:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  override def run(args: List[String]): IO[ExitCode] =
    program.useForever.as(ExitCode.Success)

  private def program: Resource[IO, Unit] =
    for
      given SessionStore[IO] <- SessionStore.inMemory[IO](30.minutes)
      factory = DemoServer().handlerFactory(McpServerConfig.default, logError)
      given Client[IO] <- EmberClientBuilder.default[IO].build
      // if you need authentication see the simple-authenticated example
      route = McpServerRoute.route(factory)
      _ <- EmberServerBuilder.default[IO]
        .withHost(Host.fromString("127.0.0.1").get)
        .withPort(Port.fromInt(18283).get)
        .withHttpApp(route.orNotFound)
        .build
    yield ()

  private def logError(error: Throwable): IO[Unit] =
    Logger[IO].warn(error)("Error while handling a request")
