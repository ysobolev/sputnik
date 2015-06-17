name := """sputnik-play"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.6"

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws,
  specs2 % Test
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
routesGenerator := InjectedRoutesGenerator

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.1" % "test"
libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "1.8.0"
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.6.4"
libraryDependencies += "org.reactivemongo" %% "reactivemongo" % "0.10.5.0.akka23"