import sbt.*

object Dependencies {
  val scala = "3.7.1"

  val cats = "2.13.0"
  val catsEffect = "3.6.1"
  val catsEffectCps = "0.3.0"
  val fs2 = "3.12.0"
  val circe = "0.14.14"
  val scalaJsonSchema = "0.2.0"
  val http4s = "0.23.30"

  val scalatest = "3.2.19"
  val scalacheck = "1.18.1"
  val scalatestScalacheck = "3.2.14.0"
  val scalacheckShapeless = "1.3.1"
  val catsEffectTesting = "1.6.0"

  val scalaLogging = "3.9.5"
  val logBinding: Seq[ModuleID] = Seq(
    "org.slf4j" % "jul-to-slf4j" % "2.0.17",
    "org.slf4j" % "jcl-over-slf4j" % "2.0.17",
    "org.slf4j" % "log4j-over-slf4j" % "2.0.17",
    "ch.qos.logback" % "logback-classic" % "1.5.18",
  )
}
