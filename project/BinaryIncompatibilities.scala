import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.core.ProblemFilters._

object BinaryIncompatibilities {
  val IR = Seq(
  )

  val Tools = Seq(
      // private[emitter], not an issue
      ProblemFilters.exclude[MissingMethodProblem](
          "org.scalajs.core.tools.linker.backend.emitter.ScalaJSEnvHolder.strongmodeenv"),
      ProblemFilters.exclude[MissingMethodProblem](
          "org.scalajs.core.tools.linker.backend.emitter.ScalaJSClassEmitter.genStaticsES6Class")
  )

  val JSEnvs = Seq(
  )

  val SbtPlugin = Seq(
  )

  val TestAdapter = Seq(
  )

  val CLI = Seq(
  )

  val Library = Seq(
    // In theory, breaking, but this is an interface in runtime that no one should extend
    ProblemFilters.exclude[MissingMethodProblem](
        "scala.scalajs.runtime.EnvironmentInfo.javaSystemProperties")
  )

  val TestInterface = Seq(
  )
}
