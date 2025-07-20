package ch.linkyard.mcp.jsonrpc2.transport

import fs2.Stream
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets

class HeaderBasedFramingSpec extends AnyFunSpec with Matchers {
  describe("HeaderBasedFraming.writeFrames") {
    it("should frame a single JSON message with Content-Length header") {
      val msg = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"foo\"}"
      val framed = Stream.emit(msg).through(HeaderBasedFraming.writeFrames[fs2.Pure]).compile.toVector
      val result = new String(framed.toArray, StandardCharsets.UTF_8)
      result shouldBe s"Content-Length: ${msg.getBytes(StandardCharsets.UTF_8).length}\r\n\r\n$msg"
    }
    it("should frame multiple messages correctly") {
      val msgs = List("{\"a\":1}", "{\"b\":2}")
      val framed = Stream.emits(msgs).through(HeaderBasedFraming.writeFrames[fs2.Pure]).compile.toVector
      val result = new String(framed.toArray, StandardCharsets.UTF_8)
      val expected = msgs.map { m =>
        s"Content-Length: ${m.getBytes(StandardCharsets.UTF_8).length}\r\n\r\n$m"
      }.mkString("")
      result shouldBe expected
    }
  }

  describe("HeaderBasedFraming.parseFramesString") {
    it("should parse a single framed message") {
      val msg = "{\"foo\":42}"
      val framed = s"Content-Length: ${msg.getBytes(StandardCharsets.UTF_8).length}\r\n\r\n$msg"
      val parsed = Stream.emit(framed).through(HeaderBasedFraming.parseFramesString[fs2.Pure]).compile.toList
      parsed shouldBe List(msg)
    }
    it("should parse multiple framed messages in one chunk") {
      val msgs = List("{\"a\":1}", "{\"b\":2}")
      val framed =
        msgs.map { m => s"Content-Length: ${m.getBytes(StandardCharsets.UTF_8).length}\r\n\r\n$m" }.mkString("")
      val parsed = Stream.emit(framed).through(HeaderBasedFraming.parseFramesString[fs2.Pure]).compile.toList
      parsed shouldBe msgs
    }
    it("should parse messages split across chunks (simulate streaming)") {
      val msg = "{\"foo\":42}"
      val framed = s"Content-Length: ${msg.getBytes(StandardCharsets.UTF_8).length}\r\n\r\n$msg"
      val (part1, part2) = framed.splitAt(10)
      val parsed = Stream.emit(
        part1
      ).append(Stream.emit(part2)).through(HeaderBasedFraming.parseFramesString[fs2.Pure]).compile.toList
      parsed shouldBe List(msg)
    }
    it("should parse multiple framed messages in small chunks") {
      val msgs = List("{\"a\":1}", "{\"b\":2}")
      val framed =
        msgs.map { m => s"Content-Length: ${m.getBytes(StandardCharsets.UTF_8).length}\r\n\r\n$m" }.mkString("")
      val parsed =
        Stream.emits(framed.grouped(10).toList).through(HeaderBasedFraming.parseFramesString[fs2.Pure]).compile.toList
      parsed shouldBe msgs
    }
    it("should ignore incomplete frames") {
      val incomplete = "Content-Length: 10\r\n\r\n12345"
      val parsed = Stream.emit(incomplete).through(HeaderBasedFraming.parseFramesString[fs2.Pure]).compile.toList
      parsed shouldBe Nil
    }
  }

  describe("HeaderBasedFraming.parseFrames") {
    it("should parse bytes into messages (round-trip)") {
      val msgs = List("{\"x\":1}", "{\"y\":2}")
      val framed = Stream.emits(msgs).through(HeaderBasedFraming.writeFrames[fs2.Pure])
      val parsed = framed.through(HeaderBasedFraming.parseFrames[fs2.Pure]).compile.toList
      parsed shouldBe msgs
    }
  }
}
