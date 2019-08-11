package sbt.internal.inc.bloop

import java.io.File
import java.nio.file.Files

import bloop.ScalaInstance
import bloop.io.AbsolutePath
import sbt.internal.inc.ZincComponentCompiler
import sbt.internal.inc.javac.AnalyzingJavaCompiler
import sbt.librarymanagement.{Configurations, ModuleID}
import xsbti.compile.{ClasspathOptions, JavaCompiler}
import xsbti.{ComponentProvider, Position}

object ZincInternals {
  def latestVersion: String = ZincComponentCompiler.incrementalVersion
  def getComponentProvider(componentsDir: AbsolutePath): ComponentProvider = {
    val componentsPath = componentsDir.underlying
    if (!Files.exists(componentsPath)) Files.createDirectory(componentsPath)
    ZincComponentCompiler.getDefaultComponentProvider(componentsPath.toFile())
  }

  private val CompileConf = Some(Configurations.Compile.name)
  def getModuleForBridgeSources(scalaInstance: ScalaInstance): ModuleID = {
    def compilerBridgeId(scalaVersion: String) = {
      // Defaults to bridge for 2.13 for Scala versions bigger than 2.13.x
      scalaVersion match {
        case sc if (sc startsWith "0.") => "dotty-sbt-bridge"
        case sc if (sc startsWith "2.10.") => "compiler-bridge_2.10"
        case sc if (sc startsWith "2.11.") => "compiler-bridge_2.11"
        case sc if (sc startsWith "2.12.") => "compiler-bridge_2.12"
        case _ => "compiler-bridge_2.13"
      }
    }

    val organization = if (scalaInstance.isDotty) scalaInstance.organization else "ch.epfl.scala"
    val bridgeId = compilerBridgeId(scalaInstance.version)
    val version = if (scalaInstance.isDotty) scalaInstance.version else latestVersion

    ModuleID(organization, bridgeId, version).withConfigurations(CompileConf).sources()
  }

  /**
   * Returns the id for the compiler interface component.
   *
   * The ID contains the following parts:
   *   - The organization, name and revision.
   *   - The bin separator to make clear the jar represents binaries.
   *   - The Scala version for which the compiler interface is meant to.
   *   - The JVM class version.
   *
   * Example: "org.scala-sbt-compiler-bridge-1.0.0-bin_2.11.7__50.0".
   *
   *
   * @param sources The moduleID representing the compiler bridge sources.
   * @param scalaInstance The scala instance that sets the scala version for the id.
   * @return The complete jar identifier for the bridge sources.
   */
  def getBridgeComponentId(sources: ModuleID, scalaInstance: ScalaInstance): String = {
    import ZincComponentCompiler.{binSeparator, javaClassVersion}
    val id = s"${sources.organization}-${sources.name}-${sources.revision}"
    val scalaVersion = scalaInstance.actualVersion()
    s"$id$binSeparator${scalaVersion}__$javaClassVersion"
  }

  import sbt.internal.inc.JavaInterfaceUtil.EnrichOptional
  object ZincExistsStartPos {
    def unapply(position: Position): Option[(Int, Int)] = {
      // Javac doesn't provide column information, so we just assume beginning of the line
      val rangePosition = for { startLine <- position.startLine().toOption.map(_.toInt) }
        yield (startLine, position.startColumn().toOption.map(_.toInt).getOrElse(0))
      lazy val pointerPosition = for { line <- position.line().toOption.map(_.toInt) }
        yield (line, position.pointer().toOption.map(_.toInt).getOrElse(0))
      rangePosition.orElse(pointerPosition)
    }
  }

  object ZincRangePos {
    def unapply(position: Position): Option[(Int, Int)] = {
      position.endLine.toOption
        .flatMap(endLine => position.endColumn().toOption.map(endColumn => (endLine, endColumn)))
    }
  }

  def instantiateJavaCompiler(
      javac: xsbti.compile.JavaCompiler,
      classpath: Seq[File],
      instance: xsbti.compile.ScalaInstance,
      cpOptions: ClasspathOptions,
      lookup: (String => Option[File]),
      searchClasspath: Seq[File]
  ): JavaCompiler = {
    new AnalyzingJavaCompiler(javac, classpath, instance, cpOptions, lookup, searchClasspath)
  }

  import sbt.internal.inc.Relations
  import sbt.internal.util.Relation
  def copyRelations(
      relations: Relations,
      rebase: File => File
  ): Relations = {
    val newSrcProd = Relation.empty ++ {
      relations.srcProd.all.map {
        case (src, classFile) => src -> rebase(classFile)
      }
    }

    Relations.make(
      newSrcProd,
      relations.libraryDep,
      relations.libraryClassName,
      relations.internalDependencies,
      relations.externalDependencies,
      relations.classes,
      relations.names,
      relations.productClassName
    )
  }
}
