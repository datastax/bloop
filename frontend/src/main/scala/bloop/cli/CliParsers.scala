package bloop.cli

import java.io.{InputStream, PrintStream}
import java.nio.file.{Path, Paths}
import java.util.Properties

import bloop.logging.DebugFilter
import caseapp.CommandParser
import caseapp.core.{ArgParser, Default, DefaultBaseCommand, Parser}
import caseapp.util.Implicit
import shapeless.LabelledGeneric

import scala.util.Try

object CliParsers {
  implicit val inputStreamRead: ArgParser[InputStream] =
    ArgParser.instance[InputStream]("stdin")(_ => Right(System.in))
  implicit val printStreamRead: ArgParser[PrintStream] =
    ArgParser.instance[PrintStream]("stdout")(_ => Right(System.out))
  implicit val pathParser: ArgParser[Path] = ArgParser.instance("path") {
    case supposedPath: String =>
      val toPath = Try(Paths.get(supposedPath)).toEither
      toPath.left.map(t => s"The provided path ${supposedPath} is not valid: '${t.getMessage()}'.")
  }

  implicit val completionFormatRead: ArgParser[completion.Format] = {
    ArgParser.instance[completion.Format]("\"bash\" | \"zsh\" | \"fish\"") {
      case "bash" => Right(completion.BashFormat)
      case "zsh" => Right(completion.ZshFormat)
      case "fish" => Right(completion.FishFormat)
      case w00t => Left(s"Unrecognized format: $w00t")
    }
  }

  implicit val parallelBatchesRead: ArgParser[ParallelBatches] = {
    ArgParser.instance[ParallelBatches]("parallel batches") {
      case s: String =>
        val int: Either[String, Int] = {
          try Right(s.toInt)
          catch { case _: NumberFormatException => Left(s"Malformed integer: $s") }
        }

        int.flatMap { i =>
          if (i > 0) Right(ParallelBatches(i))
          else Left(s"The number of parallel batches needs to greater than 0.")
        }

    }
  }

  implicit val optimizerConfigRead: ArgParser[OptimizerConfig] = {
    ArgParser.instance[OptimizerConfig]("\"debug\" | \"release\"") {
      case "debug" => Right(OptimizerConfig.Debug)
      case "release" => Right(OptimizerConfig.Release)
      case w00t => Left(s"Unrecognized optimizer config: $w00t")
    }
  }

  implicit val propertiesParser: ArgParser[CommonOptions.PrettyProperties] = {
    ArgParser.instance("A properties parser") {
      case whatever => Left("You cannot pass in properties through the command line.")
    }
  }

  val DebugFilterTags =
    "\"all\" | \"file-watching\" | \"compilation\" | \"test\" | \"bsp\" | \"link\""
  implicit val debugFilterParser: ArgParser[DebugFilter] = {
    ArgParser.instance[DebugFilter](DebugFilterTags) {
      case "all" => Right(DebugFilter.All)
      case "file-watching" => Right(DebugFilter.FileWatching)
      case "compilation" => Right(DebugFilter.Compilation)
      case "test" => Right(DebugFilter.Test)
      case "bsp" => Right(DebugFilter.Bsp)
      case "link" => Right(DebugFilter.Link)
      case w00t => Left(s"Unrecognized log context: $w00t")
    }
  }

  import shapeless.{HNil, CNil, :+:, ::, Coproduct}
  implicit val implicitHNil: Implicit[HNil] = Implicit.hnil
  implicit val implicitNone: Implicit[None.type] = Implicit.instance(None)
  implicit val implicitNoneCnil: Implicit[None.type :+: CNil] =
    Implicit.instance(Coproduct(None))

  implicit val implicitOptionDefaultString: Implicit[Option[Default[String]]] =
    Implicit.instance(Some(caseapp.core.Defaults.string))

  implicit val implicitOptionDefaultInt: Implicit[Option[Default[Int]]] =
    Implicit.instance(Some(caseapp.core.Defaults.int))

  implicit val implicitOptionDefaultBoolean: Implicit[Option[Default[Boolean]]] =
    Implicit.instance(Some(caseapp.core.Defaults.boolean))

  implicit val implicitDefaultBoolean: Implicit[Default[Boolean]] =
    Implicit.instance(caseapp.core.Defaults.boolean)

  implicit val implicitOptionDefaultOptionPath: Implicit[Option[Default[Option[Path]]]] =
    Implicit.instance(None)

  implicit val implicitOptionDefaultPrintStream: Implicit[Option[Default[PrintStream]]] =
    Implicit.instance(Some(Default.instance[PrintStream](System.out)))

  implicit val implicitOptionDefaultInputStream: Implicit[Option[Default[InputStream]]] =
    Implicit.instance(Some(Default.instance[InputStream](System.in)))

  implicit val labelledGenericCommonOptions: LabelledGeneric[CommonOptions] =
    LabelledGeneric.materializeProduct
  implicit val labelledGenericCliOptions: LabelledGeneric[CliOptions] =
    LabelledGeneric.materializeProduct
  implicit val coParser: Parser[CommonOptions] = Parser.generic
  implicit val cliParser: Parser[CliOptions] = Parser.generic

  implicit val autocompleteParser: Parser[Commands.Autocomplete] = Parser.generic
  implicit val aboutParser: Parser[Commands.About] = Parser.generic
  implicit val bspParser: Parser[Commands.Bsp] = Parser.generic
  implicit val cleanParser: Parser[Commands.Clean] = Parser.generic
  implicit val compileParser: Parser[Commands.Compile] = Parser.generic
  implicit val configureParser: Parser[Commands.Configure] = Parser.generic
  implicit val helpParser: Parser[Commands.Help] = Parser.generic
  implicit val benchmarkParser: Parser[Commands.Benchmark] = Parser.generic
  implicit val projectsParser: Parser[Commands.Projects] = Parser.generic
  implicit val runParser: Parser[Commands.Run] = Parser.generic
  implicit val testParser: Parser[Commands.Test] = Parser.generic

  val BaseMessages: caseapp.core.Messages[DefaultBaseCommand] =
    caseapp.core.Messages[DefaultBaseCommand]
  val OptionsParser: caseapp.core.Parser[CliOptions] =
    caseapp.core.Parser.apply[CliOptions]

  val CommandsMessages: caseapp.core.CommandsMessages[Commands.RawCommand] =
    caseapp.core.CommandsMessages[Commands.RawCommand]
  val CommandsParser: CommandParser[Commands.RawCommand] =
    caseapp.core.CommandParser.apply[Commands.RawCommand]
}
