package bloop.launcher

import java.io._
import java.net.Socket
import java.net.URL
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file._
import bloop.launcher.LauncherStatus.{
  FailedLauncherStatus,
  FailedToConnectToServer,
  FailedToInstallBloop,
  FailedToOpenBspConnection,
  FailedToParseArguments,
  SuccessfulRun
}
import bloop.launcher.bsp.{BspBridge, BspConnection}
import bloop.launcher.core.{
  AvailableAt,
  Feedback,
  Installer,
  ListeningAndAvailableAt,
  ResolvedAt,
  ServerStatus,
  Shell
}
import bloop.launcher.util.Environment
import scala.concurrent.Promise
import bloop.launcher.core.Shell.StatusCommand

object Launcher
    extends LauncherMain(
      System.in,
      System.out,
      System.err,
      StandardCharsets.UTF_8,
      Shell.default,
      nailgunPort = None,
      Promise[Unit](),
      Installer.defaultWebsiteURL(_)
    )

class LauncherMain(
    clientIn: InputStream,
    clientOut: OutputStream,
    val out: PrintStream,
    charset: Charset,
    val shell: Shell,
    val nailgunPort: Option[Int],
    startedServer: Promise[Unit],
    generateBloopInstallerURL: String => URL
) {
  private final val launcherTmpDir = Files.createTempDirectory(s"bsp-launcher")
  private final val bloopAdditionalArgs: List[String] = {
    nailgunPort match {
      case Some(port) => List("--nailgun-port", port.toString)
      case None => Nil
    }
  }

  def main(args: Array[String]): Unit = {
    cli(args) match {
      case SuccessfulRun => sys.exit(0)
      case _: FailedLauncherStatus => sys.exit(1)
    }
  }

  private final val SkipBspConnection = "--skip-bsp-connection"
  def cli(args0: Array[String]): LauncherStatus = {
    val (args, extraArgs) = {
      val index = args0.indexOf("--")
      if (index == -1) (args0, Array.empty[String])
      else args0.splitAt(index)
    }

    val (jvmOptions, cliOptions) = args.partition(_.startsWith("-J"))
    val (cliFlags, cliArgs) = cliOptions.toList.partition(_.startsWith("--"))
    val skipBspConnection = cliFlags.exists(_ == SkipBspConnection)
    if (cliArgs.size == 1) {
      val bloopVersion = cliArgs.apply(0)
      val bloopInstallerURL = generateBloopInstallerURL(bloopVersion)
      runLauncher(bloopVersion, bloopInstallerURL, skipBspConnection, jvmOptions.toList)
    } else {
      printError(Feedback.NoBloopVersion, out)
      FailedToParseArguments
    }
  }

  def runLauncher(
      bloopVersionToInstall: String,
      bloopInstallerURL: URL,
      skipBspConnection: Boolean,
      serverJvmOptions: List[String]
  ): LauncherStatus = {
    def failPromise(status: LauncherStatus) =
      startedServer.failure(new RuntimeException(s"The server did not start, got $status"))
    println("Starting the bsp launcher for bloop...", out)
    val bridge = new BspBridge(clientIn, clientOut, startedServer, out, shell, launcherTmpDir)

    connectToBloopBspServer(
      bloopVersionToInstall,
      bloopInstallerURL,
      skipBspConnection,
      bridge,
      serverJvmOptions
    ) match {
      case Right(Left(_)) => SuccessfulRun

      case Right(Right(Some(socket))) =>
        try{
          bridge.wireBspConnectionStreams(socket)
        } finally {
          socket.close()
        }
        SuccessfulRun

      case Right(Right(None)) =>
        failPromise(FailedToOpenBspConnection)
        FailedToOpenBspConnection

      case Left(status) =>
        failPromise(status)
        status
    }
  }

  // A connection result can be empty if no bsp connection is done, otherwise a socket
  type ConnectionResult = Either[Unit, Option[Socket]]
  def connectToBloopBspServer(
      bloopVersion: String,
      bloopInstallerURL: URL,
      skipBspConnection: Boolean,
      bridge: BspBridge,
      serverJvmOptions: List[String]
  ): Either[LauncherStatus, ConnectionResult] = {
    def ifSessionIsLive[T](establishConnection: => LauncherStatus): LauncherStatus = {
      bloopBackgroundError match {
        case Some((cmd, status)) if status.isOk =>
          printError(s"Unexpected early exit of the bloop server spawned with '$cmd'!", out)
          if (!status.output.isEmpty) printQuoted(status.output, out)
          FailedToConnectToServer

        case Some((cmd, status)) =>
          printError(s"Spawning a bloop server with '$cmd' failed!", out)
          if (!status.output.isEmpty) printQuoted(status.output, out)
          FailedToConnectToServer

        case None => establishConnection
      }
    }

    def openBspSocket(forceTcp: Boolean = false)(
        connect: Boolean => bridge.RunningBspConnection
    ): Either[LauncherStatus, Either[Unit, Option[Socket]]] = {
      val connection = connect(forceTcp)
      bridge.waitForOpenBsp(connection) match {
        case Some(c) => Right(Right(bridge.connectToOpenSession(c)))
        case None =>
          connection match {
            case bridge.RunningBspConnection(BspConnection.Tcp(_, _), _) =>
              Left {
                ifSessionIsLive {
                  printError("The launcher failed to establish a bsp connection, aborting...", out)
                  FailedToOpenBspConnection
                }
              }

            case bridge.RunningBspConnection(connection, logs) =>
              printError("Trying a tcp-based connection to the server instead...", out)
              openBspSocket(true)(connect)
          }
      }
    }

    val latestServerStatus = detectServerState(bloopVersion) match {
      case s @ Some(_) => println(Feedback.DetectedBloopinstallation, out); s
      case None => recoverFromUninstalledServer(bloopVersion, bloopInstallerURL)
    }

    latestServerStatus match {
      case Some(ListeningAndAvailableAt(binary)) =>
        if (skipBspConnection) Right(Left(()))
        else {
          openBspSocket(false) { useTcp =>
            bridge.establishBspConnectionViaBinary(binary, useTcp)
          }
        }

      case Some(AvailableAt(binary)) =>
        // Start the server if we only have a bloop binary
        startServerViaScriptInBackground(binary, serverJvmOptions)
        println("Server was started in a thread, waiting until it's up and running...", out)

        // Run `bloop about` until server is running for a max of N attempts (about 10s)
        val maxAttempts: Int = 40
        var attempts: Int = 1
        var totalMs: Long = 0
        var listening: Option[ServerStatus] = None
        while ({
          listening match {
            case Some(ListeningAndAvailableAt(_)) => false
            case _ if attempts <= maxAttempts => true
            case _ => false
          }
        }) {
          val waitMs = 250.toLong
          Thread.sleep(waitMs)
          totalMs += waitMs
          val port = nailgunPort.getOrElse(8212)
          println(s"Sleeping for ${waitMs}ms until we connect to server port $port", out)
          listening = shell.connectToBloopPort(binary, port, out)
          attempts += 1
        }

        listening match {
          case Some(ListeningAndAvailableAt(binary)) =>
            if (skipBspConnection) Right(Left(()))
            else {
              openBspSocket(false) { forceTcp =>
                bridge.establishBspConnectionViaBinary(binary, forceTcp)
              }
            }

          case _ =>
            // Let's diagnose why `bloop about` failed to run for more than 5 attempts
            Left {
              ifSessionIsLive {
                printError(s"Failed to connect to server after waiting ${totalMs}ms", out)
                FailedToConnectToServer
              }
            }
        }

      // The build server is resolved when `install.py` failed in the system
      case Some(ResolvedAt(classpath)) =>
        if (skipBspConnection) Left(FailedToInstallBloop)
        else {
          openBspSocket(false) { forceTcp =>
            bridge.runEmbeddedBspInvocationInBackground(classpath, forceTcp, serverJvmOptions)
          }
        }

      case None => Left(FailedToInstallBloop)
    }
  }

  def detectServerState(bloopVersion: String): Option[ServerStatus] = {
    shell.detectBloopInSystemPath(List("bloop") ++ bloopAdditionalArgs, out).orElse {
      // The binary is not available in the classpath
      val homeBloopDir = Environment.defaultBloopDirectory
      if (!Files.exists(homeBloopDir)) None
      else {
        // This is the nailgun script that we can use to run bloop
        val binaryName = if (Environment.isWindows) "bloop.cmd" else "bloop"
        val pybloop = homeBloopDir.resolve(binaryName)
        if (!Files.exists(pybloop)) None
        else {
          val binaryInHome = pybloop.normalize.toAbsolutePath.toString
          shell.detectBloopInSystemPath(List(binaryInHome) ++ bloopAdditionalArgs, out)
        }
      }
    }
  }

  /**
   * Checks that a bloop version can be used with the launcher.
   * Compatible bloop versions are those that are the same or bigger than 1.1.2.
   *
   * @param version The bloop version we want to install if it's missing.
   * @return Whether the version in compatible or not depending on if it can be parsed or not.
   */
  def isValidBloopVersion(version: String): Option[Boolean] = {
    import coursier.core.Version
    Version(version).items match {
      case Version.Number(major) :: Version.Number(minor) :: Version.Number(patch) :: xs =>
        Some((major == 1 && minor == 1 && patch == 2) || (major >= 1 && minor >= 2))
      case Version.Number(major) :: Version.Number(minor) :: xs =>
        Some((major >= 1 && minor >= 2))
      case xs =>
        printError(
          s"Bloop version ${version} does not follow semantic versioning, parsed as ${xs}",
          out
        )
        None
    }
  }

  /**
   * Install the server by the universal method (install.py) or, if that fails, by resolving it.
   *
   * When the server is not installed, we need to figure out a way to bring it to the user's
   * computer. Therefore, we first try the `install.py` universal method to install bloop and
   * all its scripts. This method can fail if, for example, python is not in the classpath or
   * the installation script errors. As a fallback, we resolve bloop with coursier and execute
   * an embedded cli as the last attempt to obtain a bloop instance running. This method does
   * not depend on bloop, it just requires internet connection.
   *
   * @param bloopVersion The version we want to install, passed in by the client.
   * @return An optional server state depending on whether any of the installation method succeeded.
   */
  def recoverFromUninstalledServer(
      bloopVersion: String,
      bloopInstallerURL: URL
  ): Option[ServerStatus] = {
    isValidBloopVersion(bloopVersion) match {
      case Some(true) =>
        println(Feedback.installingBloop(bloopVersion), out)
        val fullyInstalled = Installer.installBloopBinaryInHomeDir(
          launcherTmpDir,
          Environment.defaultBloopDirectory,
          bloopVersion,
          out,
          detectServerState(_),
          shell,
          bloopInstallerURL
        )

        fullyInstalled.orElse {
          println(Feedback.UseFallbackInstallation, out)
          val (bloopDependency, vanillaResolution) =
            Installer.resolveServer(bloopVersion, true, out)
          if (vanillaResolution.errors.isEmpty) {
            val jars = Installer.fetchJars(vanillaResolution, out)
            if (jars.isEmpty) None else Some(ResolvedAt(jars))
          } else {
            val stringBloopDep = Installer.fromDependencyToString(bloopDependency)
            printError(Feedback.resolvingDependencyWithNoScalaSuffix(stringBloopDep), out)

            // Before failing, let's try resolving bloop without a scala suffix to support future versions out of the box
            val (_, versionlessResolution) = Installer.resolveServer(bloopVersion, false, out)
            if (versionlessResolution.errors.isEmpty) {
              val jars = Installer.fetchJars(versionlessResolution, out)
              if (jars.isEmpty) None else Some(ResolvedAt(jars))
            } else {
              // Only report errors coming from the first resolution (second resolution was a backup)
              val prettyErrors = vanillaResolution.errors.map {
                case ((module, version), errors) =>
                  s"  $module:$version\n${errors.map("    " + _.replace("\n", "    \n")).mkString("\n")}"
              }

              printError(s"Failed to resolve '${bloopDependency}'... the server cannot start!", out)
              val errorMsg = s"Resolution error:\n${prettyErrors.mkString("\n")}"
              None
            }
          }
        }

      case _ => None
    }
  }

  // Reused across the two different ways we can run a server
  private var bloopBackgroundError: Option[(String, StatusCommand)] = None

  /**
   * Start a server in the background by using the python script `bloop server`.
   *
   * This operation can take a while in some operating systems (most notably Windows, Unix is fast).
   * After running a thread in the background, we will wait until the server is up.
   *
   * @param binary The list of arguments that make the python binary script we want to run.
   */
  def startServerViaScriptInBackground(binary: List[String], jvmOptions: List[String]): Unit = {
    // Always keep a server running in the background by making it a daemon thread
    shell.startThread("bsp-server-background", true) {
      // Running 'bloop server' should always work if v > 1.1.0
      val startCmd = {
        val cmd = nailgunPort match {
          case Some(port) => binary.diff(bloopAdditionalArgs) ++ List("server", port.toString)
          case None => binary ++ List("server")
        }

        cmd ++ jvmOptions
      }

      println(Feedback.startingBloopServer(startCmd), out)
      val status = shell.runCommand(startCmd, Environment.cwd, None)

      // Communicate to the driver logic in `connectToServer` if server failed or not
      bloopBackgroundError = Some(startCmd.mkString(" ") -> status)

      ()
    }

    ()
  }
}
