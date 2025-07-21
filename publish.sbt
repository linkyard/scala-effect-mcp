ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/linkyard/scala-effect-mcp"),
    "scm:git@github.com:linkyard/scala-effect-mcp.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id = "msiegenthaler",
    name = "Mario Siegenthaler",
    email = "mario.siegenthaler@linkyard.ch",
    url = url("https://github.com/msiegenthaler/")
  )
)

ThisBuild / homepage := Some(url("https://github.com/linkyard/scala-effect-mcp"))

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishMavenStyle := true

// new setting for the Central Portal
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
