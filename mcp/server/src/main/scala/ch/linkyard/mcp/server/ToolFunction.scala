package ch.linkyard.mcp.server

import cats.MonadThrow
import cats.implicits.*
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.Icon
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

  def apply(args: JsonObject, context: RequestContext[F]): F[Outcome[Tool.CallTool.Response]]

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
    icons: Option[List[Icon]] = None,
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
    f: (A, RequestContext[F]) => F[String],
    meta: Option[JsonObject] = None,
  ): ToolFunction[F] = new Text[F, A](info, meta, f)

  def structured[F[_]: MonadThrow, A: JsonSchemaEncoder: Decoder, B: JsonSchemaEncoder: Encoder.AsObject](
    info: Info,
    f: (A, RequestContext[F]) => F[B],
    meta: Option[JsonObject] = None,
  ): ToolFunction[F] = new Structured[F, A, B](info, meta, f)

  /** Like [[text]] but the function can ask the user questions (see [[Ask]], it runs again after each answer). */
  def interactiveText[F[_]: MonadThrow, A: JsonSchemaEncoder: Decoder](
    info: Info,
    f: (A, RequestContext[F], Ask[F]) => F[String],
    meta: Option[JsonObject] = None,
  ): ToolFunction[F] = new InteractiveText[F, A](info, meta, f)

  /** Like [[structured]] but the function can ask the user questions (see [[Ask]], it runs again after each answer). */
  def interactiveStructured[F[_]: MonadThrow, A: JsonSchemaEncoder: Decoder, B: JsonSchemaEncoder: Encoder.AsObject](
    info: Info,
    f: (A, RequestContext[F], Ask[F]) => F[B],
    meta: Option[JsonObject] = None,
  ): ToolFunction[F] = new InteractiveStructured[F, A, B](info, meta, f)

  def native[F[_]](
    info: Info,
    argsSchema: JsonSchema,
    f: (JsonObject, RequestContext[F]) => F[Outcome[CallTool.Response]],
    resultSchema: Option[JsonSchema] = None,
    meta: Option[JsonObject] = None,
  ): ToolFunction[F] = new Native[F](info, argsSchema, resultSchema, meta, f)

  /** Invalid arguments are reported as an error of the tool call (and not as a protocol error), so that the model can
    * correct them.
    */
  private def handleParsedArgs[F[_]: MonadThrow, A: Decoder](args: JsonObject)(f: A => F[Outcome[CallTool.Response]])
    : F[Outcome[CallTool.Response]] =
    args.toJson.as[A] match
      case Right(value) =>
        f(value).recover {
          case ToolError(content, meta) => Outcome.Complete(CallTool.Response.Error(content, None, meta))
        }
      case Left(error) =>
        Outcome.Complete(CallTool.Response.Error(
          List(Content.Text(s"Invalid arguments: ${error.message}")),
          None,
        )).pure[F].widen
  end handleParsedArgs

  private def schemaFor[A: JsonSchemaEncoder]: JsonSchema =
    JsonSchemaEncoder[A].schema.asObject.getOrElse(JsonObject.empty)

  private class Text[F[_]: MonadThrow, A: Decoder: JsonSchemaEncoder](
    val info: Info,
    val meta: Option[JsonObject],
    f: (A, RequestContext[F]) => F[String],
  ) extends ToolFunction[F]:
    override val argsSchema: JsonSchema = schemaFor[A]
    override val resultSchema: Option[JsonSchema] = None
    override def apply(args: JsonObject, context: RequestContext[F]): F[Outcome[Response]] =
      handleParsedArgs[F, A](args)(a =>
        f(a, context).map(text => Outcome.Complete(CallTool.Response.Success(List(Content.Text(text)), None)))
      )
  end Text

  private class Structured[F[_]: MonadThrow, A: JsonSchemaEncoder: Decoder, B: JsonSchemaEncoder: Encoder.AsObject](
    val info: Info,
    val meta: Option[JsonObject],
    f: (A, RequestContext[F]) => F[B],
  ) extends ToolFunction[F]:
    override val argsSchema: JsonSchema = schemaFor[A]
    override val resultSchema: Option[JsonSchema] = schemaFor[B].some
    override def apply(args: JsonObject, context: RequestContext[F]): F[Outcome[Response]] =
      handleParsedArgs[F, A](args)(a =>
        f(a, context).map { b =>
          val json = b.asJsonObject
          Outcome.Complete(CallTool.Response.Success(
            content = List(Content.Text(json.toJson.noSpaces)),
            json.toJson.some,
          ))
        }
      )

  private class InteractiveText[F[_]: MonadThrow, A: Decoder: JsonSchemaEncoder](
    val info: Info,
    val meta: Option[JsonObject],
    f: (A, RequestContext[F], Ask[F]) => F[String],
  ) extends ToolFunction[F]:
    override val argsSchema: JsonSchema = schemaFor[A]
    override val resultSchema: Option[JsonSchema] = None
    override def apply(args: JsonObject, context: RequestContext[F]): F[Outcome[Response]] =
      handleParsedArgs[F, A](args)(a =>
        Ask.run(context)(ask => f(a, context, ask)).map(_.map(text =>
          CallTool.Response.Success(List(Content.Text(text)), None)
        ))
      )
  end InteractiveText

  private class InteractiveStructured[
    F[_]: MonadThrow,
    A: JsonSchemaEncoder: Decoder,
    B: JsonSchemaEncoder: Encoder.AsObject,
  ](
    val info: Info,
    val meta: Option[JsonObject],
    f: (A, RequestContext[F], Ask[F]) => F[B],
  ) extends ToolFunction[F]:
    override val argsSchema: JsonSchema = schemaFor[A]
    override val resultSchema: Option[JsonSchema] = schemaFor[B].some
    override def apply(args: JsonObject, context: RequestContext[F]): F[Outcome[Response]] =
      handleParsedArgs[F, A](args)(a =>
        Ask.run(context)(ask => f(a, context, ask)).map(_.map { b =>
          val json = b.asJsonObject
          CallTool.Response.Success(List(Content.Text(json.toJson.noSpaces)), json.toJson.some)
        })
      )
  end InteractiveStructured

  private class Native[F[_]](
    val info: Info,
    val argsSchema: JsonSchema,
    val resultSchema: Option[JsonSchema],
    val meta: Option[JsonObject],
    f: (JsonObject, RequestContext[F]) => F[Outcome[Response]],
  ) extends ToolFunction[F]:
    override def apply(args: JsonObject, context: RequestContext[F]): F[Outcome[Response]] = f(args, context)
  end Native
