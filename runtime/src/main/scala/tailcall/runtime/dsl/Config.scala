package tailcall.runtime.dsl

import tailcall.runtime.ast._
import tailcall.runtime.dsl.Config._
import tailcall.runtime.http.Method
import tailcall.runtime.service.ConfigFileIO
import tailcall.runtime.transcoder.Transcoder
import zio.ZIO
import zio.json._
import zio.json.ast.Json

import java.io.File
import java.net.URL

final case class Config(version: Int = 0, server: Server = Server(), graphQL: GraphQL = GraphQL()) {
  self =>
  def compress: Config = self.copy(graphQL = self.graphQL.compress)

  def mergeRight(other: Config): Config = {
    Config(
      version = other.version,
      server = self.server.mergeRight(other.server),
      graphQL = self.graphQL.mergeRight(other.graphQL),
    )
  }

  def toBlueprint: Blueprint = toBlueprint()

  def toBlueprint(encodeSteps: Boolean = false): Blueprint = Transcoder.toBlueprint(self, encodeSteps = encodeSteps).get

  def withMutation(mutation: String): Config = self.copy(graphQL = self.graphQL.withMutation(mutation))

  def withQuery(query: String): Config = self.copy(graphQL = self.graphQL.withQuery(query))

  def withRootSchema(
    query: Option[String] = graphQL.schema.query,
    mutation: Option[String] = graphQL.schema.mutation,
  ): Config = self.copy(graphQL = self.graphQL.copy(schema = RootSchema(query, mutation)))

  def withType(input: (String, Map[String, Field])*): Config =
    self.copy(graphQL = self.graphQL.copy(types = self.graphQL.types ++ input.toMap))
}

object Config {
  def empty: Config = Config()

  def fromFile(file: File): ZIO[ConfigFileIO, Throwable, Config] = ConfigFileIO.readFile(file)

  final case class Server(baseURL: Option[URL] = None) {
    self =>
    def isEmpty: Boolean = baseURL.isEmpty

    def mergeRight(other: Server): Server = Server(baseURL = other.baseURL.orElse(self.baseURL))
  }

  final case class RootSchema(query: Option[String] = None, mutation: Option[String] = None)

  final case class GraphQL(schema: RootSchema = RootSchema(), types: Map[String, Map[String, Field]] = Map.empty) {
    self =>
    def compress: GraphQL =
      self.copy(types = self.types.map { case (k, v) => k -> v.map { case (k, v) => (k, v.compress) } })

    def mergeRight(other: GraphQL): GraphQL =
      GraphQL(
        schema = RootSchema(
          query = other.schema.query.orElse(self.schema.query),
          mutation = other.schema.mutation.orElse(self.schema.mutation),
        ),
        types = self.types ++ other.types,
      )

    def withMutation(name: String): GraphQL = copy(schema = schema.copy(mutation = Option(name)))

    def withQuery(name: String): GraphQL = copy(schema = schema.copy(query = Option(name)))

    def withSchema(query: Option[String], mutation: Option[String]): GraphQL =
      copy(schema = RootSchema(query, mutation))

    def withType(name: String, fields: Map[String, Field]): GraphQL = copy(types = types + (name -> fields))
  }

  // TODO: Field and Argument can be merged
  final case class Field(
    @jsonField("type") typeOf: String,

    // TODO: rename to `list`
    @jsonField("isList") list: Option[Boolean] = None,

    // TODO: rename to `required`
    @jsonField("isRequired") required: Option[Boolean] = None,
    steps: Option[List[Step]] = None,
    args: Option[Map[String, Arg]] = None,
  ) {
    self =>
    def apply(args: (String, Arg)*): Field = copy(args = Option(args.toMap))

    def asList: Field = copy(list = Option(true))

    def asRequired: Field = copy(required = Option(true))

    def compress: Field = {
      val isList = self.list match {
        case Some(true) => Some(true)
        case _          => None
      }

      val isRequired = self.required match {
        case Some(true) => Some(true)
        case _          => None
      }

      val steps = self.steps match {
        case Some(steps) if steps.nonEmpty =>
          Option(steps.map {
            case step @ Step.Http(_, _, _, _) =>
              val noOutputHttp = step.withOutput(None).withInput(None)
              if (step.method contains Method.GET) noOutputHttp.copy(method = None) else noOutputHttp
            case step                         => step
          })
        case _                             => None
      }

      val args = self.args match {
        case Some(args) if args.nonEmpty => Some(args.map { case (k, v) => (k, v.compress) })
        case _                           => None
      }

      self.copy(list = isList, required = isRequired, steps = steps, args = args)
    }

    def isList: Boolean = list.getOrElse(false)

    def isRequired: Boolean = required.getOrElse(false)

    def withArguments(args: Map[String, Arg]): Field = copy(args = Option(args))

    def withSteps(steps: Step*): Field = copy(steps = Option(steps.toList))
  }

  object Field {
    def apply(str: String, operations: Step*): Field =
      Field(typeOf = str, steps = if (operations.isEmpty) None else Option(operations.toList))

    def bool: Field = Field(typeOf = "Boolean")

    def int: Field = Field(typeOf = "Int")

    def ofType(name: String): Field = Field(typeOf = name)

    def string: Field = Field(typeOf = "String")
  }

  sealed trait Step
  object Step {
    @jsonHint("http")
    final case class Http(
      path: Path,
      method: Option[Method] = None,
      input: Option[TSchema] = None,
      output: Option[TSchema] = None,
    ) extends Step {
      def withInput(input: Option[TSchema]): Http = copy(input = input)

      def withMethod(method: Method): Http = copy(method = Option(method))

      def withOutput(output: Option[TSchema]): Http = copy(output = output)
    }

    @jsonHint("const")
    final case class Constant(json: Json) extends Step

    @jsonHint("objectPath")
    final case class ObjPath(map: Map[String, List[String]]) extends Step

    object Http {
      def fromEndpoint(endpoint: Endpoint): Http =
        Http(path = endpoint.path, method = Option(endpoint.method), input = endpoint.input, output = endpoint.output)
    }

    object Constant {
      implicit val codec: JsonCodec[Constant] = JsonCodec(Json.encoder, Json.decoder).transform(Constant(_), _.json)
    }

    object ObjPath {
      def apply(map: (String, List[String])*): ObjPath = ObjPath(map.toMap)
      implicit val codec: JsonCodec[ObjPath] = JsonCodec[Map[String, List[String]]].transform(ObjPath(_), _.map)
    }
  }

  final case class Arg(
    @jsonField("type") typeOf: String,

    // TODO: rename to `list`
    @jsonField("isList") list: Option[Boolean] = None,

    // TODO: rename to `required`
    @jsonField("isRequired") required: Option[Boolean] = None,
  ) {
    self =>
    def asList: Arg = self.copy(list = Option(true))

    def asRequired: Arg = self.copy(required = Option(true))

    def compress: Arg = {
      val isList = self.list match {
        case Some(true) => Some(true)
        case _          => None
      }

      val isRequired = self.required match {
        case Some(true) => Some(true)
        case _          => None
      }

      self.copy(list = isList, required = isRequired)
    }

    def isList: Boolean = list.getOrElse(false)

    def isRequired: Boolean = required.getOrElse(false)
  }
  object Arg  {
    val string: Arg               = Arg("String")
    val int: Arg                  = Arg("Int")
    val bool: Arg                 = Arg("Boolean")
    def ofType(name: String): Arg = Arg(name)
  }

  /**
   * Json Codecs
   *
   * TODO: This should only be done once, not for every
   * instance of Config. This is done currently because if
   * we create a jsonCodec from Schema, internally Maps are
   * stored as chunks and the outputted json looks like a
   * list of tuples.
   */

  implicit val urlCodec: JsonCodec[URL]                     = JsonCodec[String].transformOrFail[URL](
    string =>
      try Right(new URL(string))
      catch { case _: Throwable => Left(s"Malformed url: ${string}") },
    _.toString,
  )
  implicit val operationCodec: JsonCodec[Step]              = DeriveJsonCodec.gen[Step]
  implicit val inputTypeCodec: JsonCodec[Arg]               = DeriveJsonCodec.gen[Arg]
  implicit val fieldDefinitionCodec: JsonCodec[Field]       = DeriveJsonCodec.gen[Field]
  implicit val schemaDefinitionCodec: JsonCodec[RootSchema] = DeriveJsonCodec.gen[RootSchema]
  implicit val graphQLCodec: JsonCodec[GraphQL]             = DeriveJsonCodec.gen[GraphQL]
  implicit val serverCodec: JsonCodec[Server]               = DeriveJsonCodec.gen[Server]
  implicit val jsonCodec: JsonCodec[Config]                 = DeriveJsonCodec.gen[Config]
}