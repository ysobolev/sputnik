name := "sputnik"

version := "1.0"

scalaVersion := "2.11.6"
resolvers += "repo.novus rels" at "http://repo.novus.com/releases/"
resolvers += "repo.novus snaps" at "http://repo.novus.com/snapshots/"
libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.1" % "test"
libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "1.8.0"
libraryDependencies += "org.mongodb" %% "casbah" % "2.8.1"
libraryDependencies += "com.novus" %% "salat-core" % "1.9.9"
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.6.4"