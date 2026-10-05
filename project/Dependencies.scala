import sbt.*

object Dependencies {
  val scala = "3.10.0"

  val cats = "2.13.0"
  val catsEffect = "3.7.1"
  val catsEffectCps = "0.3.0"
  val fs2 = "3.14.0"
  val circe = "0.14.16"
  val scalaJsonSchema = "0.2.0"
  val http4s = "0.23.38"
  val ip4s = "3.8.0"

  val scalatest = "3.2.20"
  val scalacheck = "1.20.0"
  val scalatestScalacheck = "3.2.20.0"
  val scalacheckShapeless = "1.3.1"
  val catsEffectTesting = "1.8.0"

  val scalaLogging = "3.9.5"
  val logBinding: Seq[ModuleID] = Seq(
    "org.slf4j" % "jul-to-slf4j" % "2.0.20",
    "org.slf4j" % "jcl-over-slf4j" % "2.0.20",
    "org.slf4j" % "log4j-over-slf4j" % "2.0.20",
    "ch.qos.logback" % "logback-classic" % "1.6.5",
  )
}
