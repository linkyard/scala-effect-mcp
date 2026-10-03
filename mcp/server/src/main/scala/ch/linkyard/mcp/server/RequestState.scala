package ch.linkyard.mcp.server

import io.circe.Json
import io.circe.Printer
import io.circe.parser.parse
import io.circe.syntax.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/** What a request state is bound to. */
private[server] case class StateBinding(
  principal: String,
  method: String,
  /** The name of the tool/prompt or the uri of the resource */
  target: String,
  /** Hash of the arguments of the original request */
  argumentsHash: String,
)

private[server] object StateBinding:
  def hash(arguments: Json): String =
    sha256(arguments.printWith(Printer.noSpaces.copy(sortKeys = true)))

  private def sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString

/** Protects the state that a client passes back to the server: authenticated (HMAC-SHA256), limited in time and bound
  * to the user and the request it was created for. It is not encrypted, the client can read it.
  */
private[server] class RequestStateProtector(config: RequestStateConfig):
  private val Invalid = "Invalid request state"
  private val encoder = Base64.getUrlEncoder.withoutPadding
  private val decoder = Base64.getUrlDecoder

  def protect(state: String, binding: StateBinding, now: Instant): String =
    val payload = Json.obj(
      "exp" -> now.plusMillis(config.timeToLive.toMillis).getEpochSecond.asJson,
      "p" -> binding.principal.asJson,
      "m" -> binding.method.asJson,
      "t" -> binding.target.asJson,
      "h" -> binding.argumentsHash.asJson,
      "s" -> state.asJson,
    ).noSpaces.getBytes(StandardCharsets.UTF_8)
    encoder.encodeToString(payload) + "." + encoder.encodeToString(mac(config.keys.head, payload))

  /** The state, or the reason why the token is not accepted. */
  def verify(token: String, binding: StateBinding, now: Instant): Either[String, String] =
    for
      (payloadPart, macPart) <- token.lastIndexOf('.') match
        case -1 => Left(Invalid)
        case i  => Right(token.substring(0, i) -> token.substring(i + 1))
      payload <- Try(decoder.decode(payloadPart)).toEither.left.map(_ => Invalid)
      signature <- Try(decoder.decode(macPart)).toEither.left.map(_ => Invalid)
      _ <- Either.cond(
        config.keys.exists(key => MessageDigest.isEqual(mac(key, payload), signature)),
        (),
        Invalid,
      )
      json <- parse(String(payload, StandardCharsets.UTF_8)).left.map(_ => Invalid)
      cursor = json.hcursor
      exp <- cursor.get[Long]("exp").left.map(_ => Invalid)
      _ <- Either.cond(exp > now.getEpochSecond, (), "Request state expired")
      principal <- cursor.get[String]("p").left.map(_ => Invalid)
      method <- cursor.get[String]("m").left.map(_ => Invalid)
      target <- cursor.get[String]("t").left.map(_ => Invalid)
      argumentsHash <- cursor.get[String]("h").left.map(_ => Invalid)
      _ <- Either.cond(StateBinding(principal, method, target, argumentsHash) == binding, (), Invalid)
      state <- cursor.get[String]("s").left.map(_ => Invalid)
    yield state

  private def mac(key: Array[Byte], payload: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(payload)
