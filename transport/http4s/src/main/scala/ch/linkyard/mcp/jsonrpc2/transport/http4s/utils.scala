package ch.linkyard.mcp.jsonrpc2.transport.http4s

import cats.effect.IO
import org.http4s.Request
import org.http4s.Uri
import org.http4s.Uri.Path
import org.typelevel.ci.CIStringSyntax

extension (req: Request[IO])
  def serverRoot: Uri =
    val schemeStr = req.headers.get(ci"X-Forwarded-Proto").map(_.head.value).getOrElse("http")
    val hostHeader = req.headers.get(ci"X-Forwarded-Host")
      .orElse(req.headers.get(ci"Host"))
      .map(_.head.value)
      .getOrElse("localhost")

    val (host, portOpt) = hostHeader.split(":") match {
      case Array(h, p) => (h, Some(p.toInt))
      case Array(h) =>
        val defaultPort = if schemeStr == "https" then 443 else 80
        (h, Some(defaultPort))
      case _ => ("localhost", Some(80))
    }
    Uri(
      scheme = Some(Uri.Scheme.unsafeFromString(schemeStr)),
      authority = Some(Uri.Authority(host = Uri.RegName(host), port = portOpt)),
      path = Path.Root,
    )
