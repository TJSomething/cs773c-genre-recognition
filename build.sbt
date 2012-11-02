import AssemblyKeys._

assemblySettings

mainClass in assembly := Some("edu.unr.tkelly.genre.Chart")



name := "cs773c-genre-recognition"

scalaVersion := "2.9.2"

libraryDependencies ++= Seq(
      "com.googlecode.jen-api" % "jen-api" % "4.x.p",
      "org.scalanlp" %% "breeze-viz" % "0.1",
      "javolution" % "javolution" % "5.4.4",
      "log4j" % "log4j" % "1.2.15" excludeAll(
        ExclusionRule(organization = "com.sun.jdmk"),
        ExclusionRule(organization = "com.sun.jmx"),
        ExclusionRule(organization = "javax.jms")
      ),
      "commons-lang" % "commons-lang" % "2.4",
      "commons-cli" % "commons-cli" % "1.1"
)

scalacOptions ++= Seq("-unchecked", "-deprecation")