import sbt._

object Dependencies {
  val akkaVersion = "2.6.11"
  val akkaProjectionVersion = "1.1.0"
  val SlickVersion = "3.3.3"
  val akkaManagementVersion = "1.0.9"

  val common = Seq(
    "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-discovery" % akkaVersion,
    "com.lightbend.akka.management" %% "akka-management" % akkaManagementVersion,
    "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % akkaManagementVersion,
    "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaManagementVersion,
    "com.lightbend.akka" %% "akka-persistence-jdbc" % "5.0.0",
    "com.zaxxer" % "HikariCP" % "3.2.0",
    "org.postgresql" % "postgresql" % "42.1.1",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
    "org.scalamock" %% "scalamock" % "4.4.0" % Test,
    "org.scalatest" %% "scalatest" % "3.1.0" % Test
  )
}
