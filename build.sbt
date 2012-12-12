import AssemblyKeys._
 
assemblySettings

mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
  {
    case PathList("javax", "servlet", xs @ _*) => MergeStrategy.first
    case PathList("org", "apache", "jasper", xs @ _*) => MergeStrategy.first
    case PathList("org", "apache", "commons", xs @ _*) => MergeStrategy.first
    case PathList("org", "objectweb", "asm", xs @ _*) => MergeStrategy.first
    case "application.conf" => MergeStrategy.concat
    case "unwanted.txt"     => MergeStrategy.discard
    case "about.html"     => MergeStrategy.discard
    case x => old(x)
  }
}

net.virtualvoid.sbt.graph.Plugin.graphSettings

name := "cs773c-genre-recognition"

scalaVersion := "2.9.2"

mainClass in assembly := Some("edu.unr.tkelly.genre.FasterFinal")

jarName in assembly := "cs773c-final.jar"

resolvers ++= Seq(
  "spray-can-resolver-0" at "http://repo.spray.cc",
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/")

libraryDependencies ++= Seq(
      "com.googlecode.jen-api" % "jen-api" % "4.x.p",
      "org.scalanlp" %% "breeze-viz" % "0.1",
      "javolution" % "javolution" % "5.4.4",
      "log4j" % "log4j" % "1.2.15" excludeAll(
        ExclusionRule(organization = "com.sun.jdmk"),
        ExclusionRule(organization = "com.sun.jmx"),
        ExclusionRule(organization = "javax.jms")
      ),
      "commons-io" % "commons-io" % "2.4",
      "nz.ac.waikato.cms.weka" % "weka-stable" % "3.6.6" withSources(),
      "org.scalaz" %% "scalaz-core" % "6.0.4",  
      "org.spark-project" %% "spark-core" % "0.6.0" 
)

scalacOptions ++= Seq("-unchecked", "-deprecation")

