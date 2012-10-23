import AssemblyKeys._ // put this at the top of the file

assemblySettings

name := "cs773c-genre-recognition"

scalaVersion := "2.9.2"

libraryDependencies ++= Seq(
      "com.googlecode.jen-api" % "jen-api" % "4.x.p",
      "org.scalanlp" %% "breeze-viz" % "0.1"
)

