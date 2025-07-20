package ch.linkyard.mcp.jsonrpc2.transport

import fs2.text

import java.nio.charset.StandardCharsets

/** Splits/Parses streams of framing via headers (Content-Length headers) */
object HeaderBasedFraming:
  /** Pipe that takes JSON message strings and emits bytes with Content-Length framing. */
  def writeFrames[F[_]]: fs2.Pipe[F, String, Byte] =
    _.map { msg =>
      val bytes = msg.getBytes(StandardCharsets.UTF_8)
      val header = s"Content-Length: ${bytes.length}\r\n\r\n"
      header + msg
    }
      .through(text.utf8.encode)
  end writeFrames

  /** Pipe that parses a stream of bytes into JSON message strings using Content-Length framing. */
  def parseFrames[F[_]]: fs2.Pipe[F, Byte, String] =
    _.through(text.utf8.decode)
      .through(parseFramesString)

  /** Pipe that parses a stream of strings into JSON message strings using Content-Length framing. */
  def parseFramesString[F[_]]: fs2.Pipe[F, String, String] =
    def go(buffer: String, s: fs2.Stream[F, String]): fs2.Pull[F, String, Unit] = s.pull.uncons1.flatMap {
      case Some((chunk, tail)) =>
        val data = buffer + chunk
        parseOneMessage(data) match {
          case Some((msg, rest)) =>
            fs2.Pull.output1(msg) >> go(rest, tail)
          case None =>
            go(data, tail)
        }
      case None =>
        // Try to parse any remaining messages in the buffer
        def emitAll(buffer: String): fs2.Pull[F, String, Unit] =
          parseOneMessage(buffer) match {
            case Some((msg, rest)) =>
              fs2.Pull.output1(msg) >> emitAll(rest)
            case None => fs2.Pull.done
          }
        emitAll(buffer)
    }

    in => go("", in).stream
  end parseFramesString

  /** parses a single Content-Length framed message from the input string */
  private def parseOneMessage(input: String): Option[(String, String)] =
    val headerPattern = """Content-Length: (\d+)\r\n\r\n""".r
    headerPattern.findFirstMatchIn(input) match {
      case Some(m) =>
        val contentLength = m.group(1).toInt
        val headerEnd = m.end
        if input.length >= headerEnd + contentLength then
          val msg = input.substring(headerEnd, headerEnd + contentLength)
          val rest = input.substring(headerEnd + contentLength)
          Some((msg, rest))
        else None
      case None => None
    }
  end parseOneMessage
end HeaderBasedFraming
