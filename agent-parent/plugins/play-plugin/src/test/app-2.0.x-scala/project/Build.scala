import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "helloworld"
    val appVersion      = "1.0"

    val appDependencies = Seq()

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA)
}
