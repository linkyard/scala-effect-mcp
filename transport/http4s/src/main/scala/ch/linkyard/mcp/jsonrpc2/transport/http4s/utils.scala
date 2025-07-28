package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import cats.implicits.*
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Port
import org.http4s.Request
import org.http4s.Uri
import org.http4s.Uri.Host
import org.http4s.Uri.Path
import org.http4s.Uri.Scheme
import org.http4s.headers
import org.typelevel.ci.CIStringSyntax

extension (scheme: Uri.Scheme)
  def defaultPort: Port = if scheme == Scheme.https then Port.fromInt(443).get else Port.fromInt(80).get

extension (req: Request[IO])
  def serverAuthority: Option[String] =
    req.headers.get(ci"X-Forwarded-Host")
      .orElse(req.headers.get(ci"Host"))
      .map(_.head.value)

  def scheme: Uri.Scheme =
    req.headers.get[headers.Forwarded].flatMap(_.values.head.maybeProto)
      .orElse(req.headers.get[headers.`X-Forwarded-Proto`].map(_.scheme))
      .getOrElse(Scheme.http)

  private def hostPort: Option[(Uri.Host, Option[Port])] =
    req.headers.get[headers.Forwarded].flatMap(_.values.head.maybeHost.map(k => k.host -> k.port))
      .orElse(req.headers.get[headers.`X-Forwarded-Host`].flatMap(h => Host.fromString(h.host).toOption.map(_ -> h.port)))
      .orElse(req.headers.get[headers.Host].flatMap(h => Host.fromString(h.host).toOption.map(_ -> h.port)))
      .map((h, p) => h -> p.flatMap(Port.fromInt))

  def serverHost: Option[Host] =
    hostPort.map(_._1)

  def serverRoot: Uri =
    Uri(
      scheme = req.scheme.some,
      authority = Uri.Authority(
        host = req.serverHost.getOrElse[Host](Uri.RegName("localhost")),
        port = req.serverPort.map(_.value),
      ).some,
      path = Path.Root,
    )

  def clientIp: Option[IpAddress] =
    req.headers
      .get[headers.`X-Forwarded-For`]
      .flatMap(_.values.head)
      .orElse(req.remote.map(_.host))
