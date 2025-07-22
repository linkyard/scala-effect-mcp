package ch.linkyard.mcp.example.demo

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnectionHandler
import ch.linkyard.mcp.jsonrpc2.JsonRpcServer
import ch.linkyard.mcp.jsonrpc2.transport.http4s.McpServerRoute
import ch.linkyard.mcp.jsonrpc2.transport.http4s.SessionStore
import ch.linkyard.mcp.server.LowlevelMcpServer
import ch.linkyard.mcp.server.McpServer
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
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
      handler <- connectionHandler
      given SessionStore[IO] <- SessionStore.inMemory[IO](30.minutes)
      route = McpServerRoute.route(handler)
      _ <- EmberServerBuilder.default[IO]
        .withHost(Host.fromString("127.0.0.1").get)
        .withPort(Port.fromInt(18283).get)
        .withHttpApp(route.orNotFound)
        .build
    yield ()

  private def connectionHandler: Resource[IO, JsonRpcConnectionHandler[IO]] = Resource.pure(
    new JsonRpcConnectionHandler[IO]:
      override def open(conn: JsonRpcConnection[IO]): Resource[IO, Unit] =
        for
          factory = McpServer.create(new DemoServer)
          jsonRpcServer <- LowlevelMcpServer.start(factory, logError)
          _ <- Resource.make(JsonRpcServer.start[IO](jsonRpcServer, Resource.pure(conn)).useForever.start)(fiber =>
            Logger[IO].info("Stopping JsonRpcServer") >> fiber.cancel
          )
        yield ()
  )

  private def logError(error: Exception): IO[Unit] =
    Logger[IO].warn(error)(s"Error parsing request data")
