name := "play-scala"
version := "1.0-SNAPSHOT"
lazy val root = (project in file(".")).enablePlugins(PlayScala)
scalaVersion := "2.11.8"
resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
routesGenerator := InjectedRoutesGenerator
