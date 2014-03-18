/*                     __                                               *\
**     ________ ___   / /  ___      __ ____  Scala.js sbt plugin        **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013, LAMP/EPFL        **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    http://scala-js.org/       **
** /____/\___/_/ |_/____/_/ | |__/ /____/                               **
**                          |/____/                                     **
\*                                                                      */


package scala.scalajs.sbtplugin

import sbt._
import sbt.inc.{ IncOptions, ClassfileManager }
import Keys._

import java.nio.charset.Charset

import scala.collection.mutable

import SourceMapCat.catJSFilesAndTheirSourceMaps
import Utils._
import Implicits._

import scala.scalajs.tools.io.{IO => _, _}
import scala.scalajs.tools.optimizer.{ScalaJSOptimizer, ScalaJSClosureOptimizer}

import environment.{Console, LoggerConsole, RhinoBasedScalaJSEnvironment}
import environment.rhino.{CodeBlock, Utilities}

import scala.scalajs.sbtplugin.testing.TestFramework

object ScalaJSPlugin extends Plugin {
  val scalaJSVersion = "0.4.1-SNAPSHOT"
  val scalaJSIsSnapshotVersion = scalaJSVersion endsWith "-SNAPSHOT"
  val scalaJSScalaVersion = "2.10.2"

  object ScalaJSKeys {
    val packageJS = taskKey[Seq[File]]("Package all the compiled .js files")
    val preoptimizeJS = taskKey[File]("Package and pre-optimize all the compiled .js files in one file")
    val optimizeJS = taskKey[File]("Package and optimize all the compiled .js files in one file")

    val packageExternalDepsJS = taskKey[Seq[File]]("Package the .js files of external dependencies")
    val packageInternalDepsJS = taskKey[Seq[File]]("Package the .js files of internal dependencies")
    val packageExportedProductsJS = taskKey[Seq[File]]("Package the .js files the project")

    val excludeDefaultScalaLibrary = settingKey[Boolean](
        "Exclude the default Scala library from the classpath sent to Scala.js")

    val optimizeJSPrettyPrint = settingKey[Boolean](
        "Pretty-print the output of optimizeJS")
    val optimizeJSExterns = taskKey[Seq[File]](
        "Extern files to use with optimizeJS")

    val loggingConsole = taskKey[Option[Console]](
        "The logging console used by the Scala.js jvm environment")
    val scalaJSEnvironment = taskKey[ScalaJSEnvironment](
        "A JVM-like environment where Scala.js files can be run and tested")

    val scalaJSSetupRunner = settingKey[Boolean](
        "Configure the run task to run the main object with the Scala.js environment")

    val scalaJSTestBridgeClass = settingKey[String](
        "The Scala.js class that delegates test calls to the given test framework")
    val scalaJSTestFramework = settingKey[String](
        "The Scala.js class that is used as a test framework, for example a class that wraps Jasmine")

    val relativeSourceMaps = settingKey[Boolean](
        "Make the referenced paths on source maps relative to target path")
  }

  import ScalaJSKeys._

  private def isJarWithPrefix(prefixes: String*)(item: File): Boolean = {
    item.name.endsWith(".jar") && prefixes.exists(item.name.startsWith)
  }

  val isScalaJSCompilerJar = isJarWithPrefix(
      "scala-library", "scala-compiler", "scala-reflect", "scalajs-compiler",
      "scala-parser-combinators", "scala-xml") _

  private val AncestorCountLine =
    raw""""ancestorCount": *([0-9]+)""".r.unanchored

  def sortScalaJSOutputFiles(files: Seq[File]): Seq[File] = {
    files sortWith { (lhs, rhs) =>
      def rankOf(jsFile: File): Int = {
        jsFile match {
          case CoreJSLibFile() => -1
          case ScalaJSClassFile(infoFile) =>
            IO.readLines(infoFile).collectFirst {
              case AncestorCountLine(countStr) => countStr.toInt
            }.getOrElse {
              throw new AssertionError(s"Did not find ancestor count in $infoFile")
            }
          case _ => 10000 // just in case someone does something weird to our classpaths
        }
      }
      val lhsRank = rankOf(lhs)
      val rhsRank = rankOf(rhs)
      if (lhsRank != rhsRank) lhsRank < rhsRank
      else lhs.name.compareTo(rhs.name) < 0
    }
  }

  /** Partitions a sequence of files in three categories:
   *  1. The core js lib (must be unique)
   *  2. Scala.js class files (.js files emitted by Scala.js)
   *  3. Other scripts
   */
  def partitionJSFiles(files: Seq[File]): (File, Seq[File], Seq[File]) = {
    val (classFiles, otherFiles) = files.partition(isScalaJSClassFile)
    val (coreJSLibFiles, customScripts) = otherFiles.partition(isCoreJSLibFile)

    if (coreJSLibFiles.size != 1)
      throw new IllegalArgumentException(
          s"There must be exactly one Scala.js core library (scalajs-corejslib.js) in the inputs.")

    (coreJSLibFiles.head, classFiles, customScripts)
  }

  def packageClasspathJSTasks(classpathKey: TaskKey[Classpath],
      packageJSKey: TaskKey[Seq[File]],
      outputSuffix: String): Seq[Setting[_]] = Seq(

      classpathKey in packageJSKey := {
        val s = streams.value
        val originalClasspath = classpathKey.value

        val taskCacheDir = s.cacheDirectory / "package-js"
        IO.createDirectory(taskCacheDir)

        val taskExtractDir = taskCacheDir / "extracted-jars"
        IO.createDirectory(taskExtractDir)

        def fileID(file: File) =
          file.name + "-" + Integer.toString(file.getPath.##, 16)

        // List cp directories, and jars to extract and where

        val cpDirectories = new mutable.ListBuffer[Attributed[File]]
        val jars = mutable.Set.empty[File]

        for (cpEntry <- originalClasspath) {
          val cpFile = cpEntry.data

          if (cpFile.isDirectory) {
            cpDirectories += cpEntry
          } else if (cpFile.isFile && !isScalaJSCompilerJar(cpFile)) {
            val extractDir = taskExtractDir / fileID(cpFile)
            jars += cpFile
            cpDirectories += Attributed.blank(extractDir)
          }
        }

        // Extract jars

        val cachedExtractJars = FileFunction.cached(taskCacheDir / "extract-jars")(
            FilesInfo.lastModified, FilesInfo.exists) { (inReport, outReport) =>

          val usefulFilesFilter = ("*.sjsinfo": NameFilter) | "*.js" | "*.js.map"

          for (jar <- inReport.modified -- inReport.removed) {
            s.log.info("Extracting %s ..." format jar)
            val extractDir = taskExtractDir / fileID(jar)
            if (extractDir.exists)
              IO.delete(extractDir)

            IO.createDirectory(extractDir)
            IO.unzip(jar, extractDir, filter = usefulFilesFilter,
                preserveLastModified = true)
          }

          for (jar <- inReport.removed) {
            val extractDir = taskExtractDir / fileID(jar)
            if (extractDir.exists)
              IO.delete(extractDir)
          }

          (taskExtractDir ** usefulFilesFilter).get.toSet
        }

        cachedExtractJars(jars.toSet)

        cpDirectories
      },

      unmanagedSources in packageJSKey := Seq(),

      managedSources in packageJSKey := {
        // List input files (files in earlier dirs shadow files in later dirs)
        val cp = (classpathKey in packageJSKey).value

        val existingPaths = mutable.Set.empty[String]
        val inputs = new mutable.ListBuffer[File]

        for (dir <- cp.map(_.data)) {
          for {
            file <- (dir ** "*.js").get
            if isScalaJSClassFile(file) || isCoreJSLibFile(file)
          } {
            val path = IO.relativize(dir, file).get
            if (!existingPaths.contains(path)) {
              inputs += file
              existingPaths += path
            }
          }
        }

        inputs.result()
      },

      sources in packageJSKey := {
        ((managedSources in packageJSKey).value ++
            (unmanagedSources in packageJSKey).value)
      },

      moduleName in packageJSKey := moduleName.value,

      artifactPath in packageJSKey :=
        ((crossTarget in packageJSKey).value /
            ((moduleName in packageJSKey).value + outputSuffix + ".js")),

      packageJSKey := {
        val s = streams.value
        val inputs = (sources in packageJSKey).value
        val output = (artifactPath in packageJSKey).value
        val taskCacheDir = s.cacheDirectory / "package-js"

        IO.createDirectory(new File(output.getParent))

        if (inputs.isEmpty) {
          if (!output.isFile || output.length != 0)
            IO.writeLines(output, Nil)
        } else {
          FileFunction.cached(taskCacheDir / "package",
              FilesInfo.lastModified, FilesInfo.exists) { dependencies =>
            s.log.info("Packaging %s ..." format output)
            val sorted = sortScalaJSOutputFiles(inputs)
            catJSFilesAndTheirSourceMaps(sorted, output, relativeSourceMaps.value)
            Set(output)
          } (inputs.toSet)
        }

        Seq(output)
      }
  )

  /** Patches the IncOptions so that .js and .js.map files are pruned as needed.
   *
   *  This complicated logic patches the ClassfileManager factory of the given
   *  IncOptions with one that is aware of .js, .js.map and .sjsinfo files
   *  emitted by the Scala.js compiler. This makes sure that, when a .class
   *  file must be deleted, the corresponding .js, .js.map and .sjsinfo files
   *  are also deleted.
   */
  def scalaJSPatchIncOptions(incOptions: IncOptions): IncOptions = {
    val inheritedNewClassfileManager = incOptions.newClassfileManager
    val newClassfileManager = () => new ClassfileManager {
      val inherited = inheritedNewClassfileManager()

      def delete(classes: Iterable[File]): Unit = {
        inherited.delete(classes flatMap { classFile =>
          val scalaJSFiles = if (classFile.getPath endsWith ".class") {
            for {
              ext <- List(".js", ".js.map", ".sjsinfo")
              f = changeExt(classFile, ".class", ext)
              if f.exists
            } yield f
          } else Nil
          classFile :: scalaJSFiles
        })
      }

      def generated(classes: Iterable[File]): Unit = inherited.generated(classes)
      def complete(success: Boolean): Unit = inherited.complete(success)
    }
    incOptions.copy(newClassfileManager = newClassfileManager)
  }

  val scalaJSEnvironmentTask = Def.task[ScalaJSEnvironment] {
    val inputs = (sources in scalaJSEnvironment).value
    val classpath = (fullClasspath in scalaJSEnvironment).value.map(_.data)
    val logger = streams.value.log
    val console = loggingConsole.value

    new RhinoBasedScalaJSEnvironment(inputs, classpath, console, logger.trace)
  }

  val scalaJSEnvironmentSettings = Seq(
      sources in scalaJSEnvironment := (
          (sources in packageExternalDepsJS).value ++
          (sources in packageInternalDepsJS).value ++
          (sources in packageExportedProductsJS).value
      ),

      fullClasspath in scalaJSEnvironment := (
          (externalDependencyClasspath in packageExternalDepsJS).value ++
          (internalDependencyClasspath in packageInternalDepsJS).value ++
          (exportedProducts in packageExportedProductsJS).value
      ),

      scalaJSEnvironment <<= scalaJSEnvironmentTask
  )

  val scalaJSConfigSettings: Seq[Setting[_]] = Seq(
      incOptions ~= scalaJSPatchIncOptions
  ) ++ (
      packageClasspathJSTasks(externalDependencyClasspath,
          packageExternalDepsJS, "-extdeps") ++
      packageClasspathJSTasks(internalDependencyClasspath,
          packageInternalDepsJS, "-intdeps") ++
      packageClasspathJSTasks(exportedProducts,
          packageExportedProductsJS, "")
  ) ++ (
      scalaJSEnvironmentSettings
  ) ++ Seq(
      managedSources in packageJS := Seq(),
      unmanagedSources in packageJS := Seq(),
      sources in packageJS := {
        ((managedSources in packageJS).value ++
            (unmanagedSources in packageJS).value)
      },

      sources in packageExportedProductsJS ++=
        (sources in packageJS).value,

      packageJS := (
          packageExternalDepsJS.value ++
          packageInternalDepsJS.value ++
          packageExportedProductsJS.value
      ),

      sources in preoptimizeJS := (
          (sources in packageExternalDepsJS).value ++
          (sources in packageInternalDepsJS).value ++
          (sources in packageExportedProductsJS).value
      ),

      artifactPath in preoptimizeJS :=
        ((crossTarget in preoptimizeJS).value /
            ((moduleName in preoptimizeJS).value + "-preopt.js")),

      preoptimizeJS := {
        val s = streams.value
        val inputs = (sources in preoptimizeJS).value
        val output = (artifactPath in preoptimizeJS).value
        val taskCacheDir = s.cacheDirectory / "preoptimize-js"

        IO.createDirectory(output.getParentFile)

        if (inputs.isEmpty) {
          if (!output.isFile || output.length != 0)
            IO.writeLines(output, Nil)
        } else {
          FileFunction.cached(taskCacheDir,
              FilesInfo.lastModified, FilesInfo.exists) { dependencies =>
            s.log.info("Preoptimizing %s ..." format output)
            import ScalaJSOptimizer._
            val (coreJSLibFile, classFiles, customScripts) =
              partitionJSFiles(inputs)
            val coreInfoFiles =
              Seq("javalangObject.sjsinfo", "javalangString.sjsinfo").map(
                  name => FileVirtualFile.withName(coreJSLibFile, name))
            val optimizer = new ScalaJSOptimizer
            val result = optimizer.optimize(
                Inputs(
                    coreJSLib = FileVirtualJSFile(coreJSLibFile),
                    coreInfoFiles = coreInfoFiles map FileVirtualFile,
                    scalaJSClassfiles = classFiles map FileVirtualScalaJSClassfile,
                    customScripts = customScripts map FileVirtualJSFile),
                OutputConfig(
                    name = output.name),
                s.log)
            IO.write(output, result.output.content, Charset.forName("UTF-8"), false)
            Set(output)
          } (inputs.toSet)
        }

        output
      },

      managedSources in optimizeJS := Seq(preoptimizeJS.value),
      unmanagedSources in optimizeJS := Seq(),
      sources in optimizeJS := {
        ((managedSources in optimizeJS).value ++
            (unmanagedSources in optimizeJS).value)
      },

      moduleName in optimizeJS := moduleName.value,

      artifactPath in optimizeJS :=
        ((crossTarget in optimizeJS).value /
            ((moduleName in optimizeJS).value + "-opt.js")),

      optimizeJS := {
        val s = streams.value
        val inputs = (sources in optimizeJS).value
        val output = (artifactPath in optimizeJS).value
        val taskCacheDir = s.cacheDirectory / "optimize-js"

        IO.createDirectory(output.getParentFile)

        FileFunction.cached(taskCacheDir,
            FilesInfo.lastModified, FilesInfo.exists) { dependencies =>
          s.log.info("Optimizing %s ..." format output)
          import ScalaJSClosureOptimizer._
          val optimizer = new ScalaJSClosureOptimizer
          val result = optimizer.optimize(
              Inputs(
                  sources = inputs map FileVirtualJSFile,
                  additionalExterns = optimizeJSExterns.value map FileVirtualJSFile),
              OutputConfig(
                  name = output.name,
                  prettyPrint = optimizeJSPrettyPrint.value),
              s.log)
          IO.write(output, result.output.content, Charset.forName("UTF-8"), false)
          Set(output)
        } (inputs.toSet)

        output
      }
  )

  lazy val scalaJSRunnerTask = Def.task[ScalaRun] {
    new ScalaJSEnvRun(scalaJSEnvironment.value)
  }

  val scalaJSRunSettings = Seq(
      scalaJSSetupRunner := true,
      runner in run <<= Def.taskDyn {
        if (scalaJSSetupRunner.value)
          scalaJSRunnerTask
        else
          runner in run
      }
  )

  val scalaJSCompileSettings = (
      scalaJSConfigSettings ++
      scalaJSRunSettings
  )

  val scalaJSTestFrameworkSettings = Seq(
      scalaJSTestFramework := "scala.scalajs.test.JasmineTestFramework",
      scalaJSTestBridgeClass := "scala.scalajs.test.JasmineTestBridge",

      loadedTestFrameworks := {
        val loader = testLoader.value
        val isTestFrameworkDefined = try {
          Class.forName(scalaJSTestFramework.value, false, loader)
          true
        } catch {
          case _: ClassNotFoundException => false
        }
        if (isTestFrameworkDefined) {
          loadedTestFrameworks.value.updated(
              sbt.TestFramework(classOf[TestFramework].getName),
              new TestFramework(
                  environment = scalaJSEnvironment.value,
                  testRunnerClass = scalaJSTestBridgeClass.value,
                  testFramework = scalaJSTestFramework.value)
          )
        } else {
          loadedTestFrameworks.value
        }
      }
  )

  val scalaJSTestSettings = (
      scalaJSConfigSettings ++
      scalaJSTestFrameworkSettings
  ) ++ (
      Seq(packageExternalDepsJS, packageInternalDepsJS,
          packageExportedProductsJS,
          preoptimizeJS, optimizeJS) map { packageJSTask =>
        moduleName in packageJSTask := moduleName.value + "-test"
      }
  )

  def defaultLoggingConsole =
      loggingConsole := Some(new LoggerConsole(streams.value.log))

  val scalaJSDefaultConfigs = (
      inConfig(Compile)(scalaJSCompileSettings) ++
      inConfig(Test)(scalaJSTestSettings)
  )

  val scalaJSProjectBaseSettings = Seq(
      excludeDefaultScalaLibrary := false,

      relativeSourceMaps := false,
      optimizeJSPrettyPrint := false,
      optimizeJSExterns := Seq(),

      defaultLoggingConsole
  )

  val scalaJSAbstractSettings: Seq[Setting[_]] = (
      scalaJSProjectBaseSettings ++
      scalaJSDefaultConfigs
  )

  val scalaJSReleasesResolver = Resolver.url("scala-js-releases",
      url("http://dl.bintray.com/content/scala-js/scala-js-releases"))(
      Resolver.ivyStylePatterns)
  val scalaJSSnapshotsResolver = Resolver.url("scala-js-snapshots",
      url("http://repo.scala-js.org/repo/snapshots/"))(
      Resolver.ivyStylePatterns)

  val scalaJSSettings: Seq[Setting[_]] = scalaJSAbstractSettings ++ Seq(
      // the resolver to find the compiler and library (and others)
      resolvers ++= Seq(scalaJSReleasesResolver, scalaJSSnapshotsResolver),

      // you will need the Scala.js compiler plugin
      autoCompilerPlugins := true,
      addCompilerPlugin("org.scala-lang.modules.scalajs" %% "scalajs-compiler" % scalaJSVersion),

      // and of course the Scala.js library
      libraryDependencies += "org.scala-lang.modules.scalajs" %% "scalajs-library" % scalaJSVersion
  )
}
