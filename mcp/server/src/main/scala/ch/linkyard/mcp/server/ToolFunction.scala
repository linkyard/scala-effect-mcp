package ch.linkyard.mcp.server

import cats.MonadThrow
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.JsonSchema
import ch.linkyard.mcp.protocol.Meta
import ch.linkyard.mcp.protocol.Tool
import ch.linkyard.mcp.protocol.Tool.CallTool
import ch.linkyard.mcp.protocol.Tool.CallTool.Response
import com.melvinlow.json.schema.JsonSchemaEncoder
import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

sealed trait ToolFunction[F[_]]:
  def name: String = info.name
  val info: ToolFunction.Info

  /** Additional infos (_meta) */
  val meta: Option[JsonObject]

  val argsSchema: JsonSchema
  val resultSchema: Option[JsonSchema]

  def apply(args: JsonObject, context: CallContext[F]): F[Tool.CallTool.Response]

  override def hashCode(): Int = name.hashCode()
  override def equals(that: Any): Boolean = that match
    case f: ToolFunction[?] => name == f.name
    case _                  => false
  override def toString(): String = s"ToolFunction(${info.name})"
end ToolFunction

object ToolFunction:
  case class Info(
    /** Unique identifier */
    name: String,
    /** Human readable name */
    title: Option[String],
    /** Human readable description of the functionality */
    description: Option[String],
    effect: ToolFunction.Effect,
    /** Does this tool interact with external entities */
    isOpenWorld: Boolean,
  ):
    def isReadOnly: Boolean = effect == Effect.ReadOnly
    def isIdempotent: Boolean = effect match
      case Effect.ReadOnly                => true
      case Effect.Additive(idempotent)    => idempotent
      case Effect.Destructive(idempotent) => idempotent
    def isDestructive: Boolean = effect match
      case Effect.ReadOnly       => false
      case Effect.Additive(_)    => false
      case Effect.Destructive(_) => true

  enum Effect:
    case ReadOnly
    case Additive(idempotent: Boolean)
    case Destructive(idempotent: Boolean)

  case class ToolError(content: List[Content], _meta: Meta = Meta.empty) extends RuntimeException("Tool error")

  def text[F[_]: MonadThrow, A: JsonSchemaEncoder: Decoder](
    info: Info,
    f: (A, CallContext[F]) => F[String],
    meta: Option[JsonObject] = None,
  ): ToolFunction[F] = new Text[F, A](info, meta, f)

  def structured[F[_]: MonadThrow, A: JsonSchemaEncoder: Decoder, B: JsonSchemaEncoder: Encoder.AsObject](
    info: Info,
    f: (A, CallContext[F]) => F[B],
    meta: Option[JsonObject] = None,
  ): ToolFunction[F] = new Structured[F, A, B](info, meta, f)

  def native[F[_]](
    info: Info,
    argsSchema: JsonSchema,
    f: (JsonObject, CallContext[F]) => F[CallTool.Response],
    resultSchema: Option[JsonSchema] = None,
    meta: Option[JsonObject] = None,
  ): ToolFunction[F] = new Native[F](info, argsSchema, resultSchema, meta, f)

  private def handleParsedArgs[F[_]: MonadThrow, A: Decoder](args: JsonObject)(f: A => F[CallTool.Response])
    : F[CallTool.Response] =
    args.toJson.as[A] match
      case Right(value) =>
        f(value).recover {
          case ToolError(content, meta) => CallTool.Response.Error(content, meta)
        }
      case Left(error) =>
        McpError.raise(ErrorCode.InvalidParams, s"Argument parsing failed: ${error.message}").widen
  end handleParsedArgs

  private def schemaFor[A: JsonSchemaEncoder]: JsonSchema =
    JsonSchemaEncoder[A].schema.asObject.getOrElse(JsonObject.empty)

  private class Text[F[_]: MonadThrow, A: Decoder: JsonSchemaEncoder](
    val info: Info,
    val meta: Option[JsonObject],
    f: (A, CallContext[F]) => F[String],
  ) extends ToolFunction[F]:
    override val argsSchema: JsonSchema = schemaFor[A]
    override val resultSchema: Option[JsonSchema] = None
    override def apply(args: JsonObject, context: CallContext[F]): F[Response] =
      handleParsedArgs[F, A](args)(a =>
        f(a, context).map(text => CallTool.Response.Success(List(Content.Text(text)), None))
      )
  end Text

  private class Structured[F[_]: MonadThrow, A: JsonSchemaEncoder: Decoder, B: JsonSchemaEncoder: Encoder.AsObject](
    val info: Info,
    val meta: Option[JsonObject],
    f: (A, CallContext[F]) => F[B],
  ) extends ToolFunction[F]:
    override val argsSchema: JsonSchema = schemaFor[A]
    override val resultSchema: Option[JsonSchema] = schemaFor[B].some
    override def apply(args: JsonObject, context: CallContext[F]): F[Response] =
      handleParsedArgs[F, A](args)(a =>
        f(a, context).map { b =>
          val json = b.asJsonObject
          CallTool.Response.Success(
            content = List(Content.Text(json.toJson.noSpaces)),
            json.some,
          )
        }
      )

  private class Native[F[_]](
    val info: Info,
    val argsSchema: JsonSchema,
    val resultSchema: Option[JsonSchema],
    val meta: Option[JsonObject],
    f: (JsonObject, CallContext[F]) => F[Response],
  ) extends ToolFunction[F]:
    override def apply(args: JsonObject, context: CallContext[F]): F[Response] = f(args, context)
  end Native
