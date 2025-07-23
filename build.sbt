ThisBuild / scalaVersion := Dependencies.scala
ThisBuild / organization := "ch.linkyard.mcp"
ThisBuild / organizationName := "linkyard ag"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / description := "Library to implement model context protocol servers (MCP) in scala using fs2 and cats effect."
ThisBuild / licenses := Seq("MIT" -> url("https://mit-license.org/"))

lazy val root = (project in file("."))
  .settings(
    name := "scala-effect-mcp",
    inThisBuild(List(
      Global / onChangedBuildSource := ReloadOnSourceChanges,
      usePipelining := false,
      scalacOptions += "-source:3.7",
      scalacOptions += "-unchecked",
      scalacOptions += "-deprecation",
      scalacOptions += "-feature",
      scalacOptions += "-preview",
      scalacOptions += "-new-syntax",
      scalacOptions += "-Wconf:any:e",
      scalacOptions += "-Wconf:msg=Given search preference:s",
      scalacOptions += "-Wconf:id=E198:w",
      scalacOptions += "-Wconf:msg=unused pattern variable:s",
      scalacOptions += "-Wvalue-discard",
      scalacOptions += "-Wunused:all",
      scalacOptions ++= Seq("-Xmax-inlines", "50"),
      semanticdbEnabled := true,
      ThisBuild / turbo := true,
      Compile / packageDoc / publishArtifact := false,
      Global / cancelable := true,
      assembly / assemblyMergeStrategy := {
        case PathList("reference.conf")                               => MergeStrategy.concat
        case PathList("META-INF", "io.netty.versions.properties")     => MergeStrategy.last
        case PathList("module-info.class")                            => MergeStrategy.last
        case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
        case PathList("com", "sun", "activation", "registries", _)    => MergeStrategy.last
        case other =>
          val oldStrategy = (assembly / assemblyMergeStrategy).value
          oldStrategy(other)
      },
      publish / skip := true,
      Compile / packageDoc / publishArtifact := true,
      Compile / packageSrc / publishArtifact := true,
      libraryDependencies ++= Dependencies.logBinding.map(_ % Test),
      libraryDependencies ++= Seq(
        "io.circe" %% "circe-literal" % Dependencies.circe,
        "io.circe" %% "circe-parser" % Dependencies.circe,
        "org.typelevel" %% "cats-effect-testing-scalatest" % Dependencies.catsEffectTesting,
        "org.scalatest" %% "scalatest" % Dependencies.scalatest,
        "org.scalacheck" %% "scalacheck" % Dependencies.scalacheck,
        "org.scalatestplus" %% "scalacheck-1-16" % Dependencies.scalatestScalacheck ,
      ).map(_ % Test),
      publish := {},
    )),
  )
  .aggregate(
    jsonrpc2,
    transportStdio,
    transportHttp4s,
    mcpProtocol,
    mcpServer,
    exampleSimpleEcho,
    exampleDemo,
    exampleDemoHttp,
  )

ThisBuild / commands += Command.command("cleanup") { state =>
  "root/scalafix" :: "root/Test/scalafix" ::
  "root/scalafmt" :: "root/Test/scalafmt" ::
  state
}

lazy val jsonrpc2 = (project in file("jsonrpc2"))
  .settings(
    name := "jsonrpc2",
    publish / skip := false,
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % Dependencies.fs2,
      "io.circe" %% "circe-core" % Dependencies.circe,
    ),
  )

lazy val transportStdio = (project in file("transport/stdio"))
  .settings(
    name := "jsonrpc2-stdio",
    publish / skip := false,
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % Dependencies.fs2,
      "io.circe" %% "circe-parser" % Dependencies.circe,
    ),
  ).dependsOn(jsonrpc2)

lazy val transportHttp4s = (project in file("transport/http4s"))
  .settings(
    name := "mcp-server-http4s",
    publish / skip := false,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % Dependencies.http4s,
      "org.http4s" %% "http4s-server" % Dependencies.http4s,
      "org.http4s" %% "http4s-client" % Dependencies.http4s,
      "org.http4s" %% "http4s-circe" % Dependencies.http4s,
    ),
  ).dependsOn(jsonrpc2)

lazy val mcpProtocol = (project in file("mcp/protocol"))
  .settings(
    name := "mcp-protocol",
    publish / skip := false,
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % Dependencies.circe,
    ),
  ).dependsOn(jsonrpc2)

lazy val mcpServer = (project in file("mcp/server"))
  .settings(
    name := "mcp-server",
    publish / skip := false,
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % Dependencies.fs2,
      "co.fs2" %% "fs2-io" % Dependencies.fs2,
      "io.circe" %% "circe-core" % Dependencies.circe,
      "io.circe" %% "circe-parser" % Dependencies.circe,
      "io.circe" %% "circe-generic" % Dependencies.circe,
      "com.melvinlow" %% "scala-json-schema" % Dependencies.scalaJsonSchema,
    ),
  ).dependsOn(jsonrpc2, mcpProtocol)


lazy val exampleSimpleEcho = (project in file("example/simple-echo"))
  .settings(
    name := "example-simple-echo",
    run / fork := true,
    publish / skip := true,
    assembly / aggregate := true,
    assembly / mainClass := Some("ch.linkyard.mcp.example.simpleEcho.SimpleEchoServer"),
    assembly / assemblyJarName := "echo.jar",
    assembly / test := {},

    libraryDependencies ++= Dependencies.logBinding,
  )
  .dependsOn(mcpServer, transportStdio)


lazy val exampleSimpleAuthenticated = (project in file("example/simple-authenticated"))
  .settings(
    name := "example-simple-authenticated",
    run / fork := true,
    assembly / aggregate := true,
    assembly / mainClass := Some("ch.linkyard.mcp.example.simpleAuthenticated.SimpleAuthenticatedServer"),
    assembly / assemblyJarName := "simple-authenticated.jar",
    assembly / test := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % Dependencies.http4s,
      "org.http4s" %% "http4s-ember-client" % Dependencies.http4s,
    ),
    libraryDependencies ++= Dependencies.logBinding,
  )
  .dependsOn(mcpServer, transportHttp4s)

lazy val exampleDemo = (project in file("example/demo"))
  .settings(
    name := "example-demo",
    run / fork := true,
    assembly / aggregate := true,
    assembly / mainClass := Some("ch.linkyard.mcp.example.demo.StdioDemoMcpServer"),
    assembly / assemblyJarName := "demo.jar",
    assembly / test := {},
    publish / skip := true,
    libraryDependencies ++= Dependencies.logBinding,
  )
  .dependsOn(mcpServer, transportStdio)

lazy val exampleDemoHttp = (project in file("example/demo-http"))
  .settings(
    name := "example-demo-http",
    run / fork := true,
    assembly / aggregate := true,
    assembly / mainClass := Some("ch.linkyard.mcp.example.demo.HttpDemoMcpServer"),
    assembly / assemblyJarName := "demo-http.jar",
    assembly / test := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % Dependencies.http4s,
      "org.http4s" %% "http4s-ember-client" % Dependencies.http4s,
    ),
    libraryDependencies ++= Dependencies.logBinding,
  )
  .dependsOn(exampleDemo, transportHttp4s)
