val nativeProject = project
  .enablePlugins(ScalaNativePlugin)
  .settings(
    scalaVersion := "2.11.12",
    InputKey[Unit]("check") := {
      val expected = complete.DefaultParsers.spaceDelimited("").parsed.head
      val config = bloopConfigDir.value / s"${thisProject.value.id}.json"
      val lines = IO.read(config).replaceAll("\\s", "")
      assert(lines.contains(s""""platform":{"name":"$expected""""))
      assert(lines.contains(""""mode":"debug""""))
      assert(lines.contains(s""""version":"0.3.9""""))
    }
  )
