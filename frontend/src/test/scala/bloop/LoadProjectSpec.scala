package bloop

import bloop.config.Config
import bloop.data.Project
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestUtil
import org.junit.Test

class LoadProjectSpec {
  @Test def LoadJavaProject(): Unit = {
    // Make sure that when no scala setup is configured the project load succeeds (and no scala instance is defined)
    val logger = new RecordingLogger()
    val config0 = Config.File.dummyForTests
    val project = config0.project
    val configWithNoScala = config0.copy(config0.version, project.copy(scala = None))
    val origin = TestUtil.syntheticOriginFor(AbsolutePath.completelyUnsafe(""))
    val inferredInstance = Project.fromConfig(configWithNoScala, origin, logger).scalaInstance
    assert(inferredInstance.isEmpty)
  }
}
