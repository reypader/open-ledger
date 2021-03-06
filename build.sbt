ThisBuild / organization := "io.openledger"

ThisBuild / scalaVersion := "2.13.1"

ThisBuild / scalacOptions ++= Seq("-feature")

lazy val root = (project in file("."))
  .aggregate(ledger_events, ledger_processor, ledger_projection, simulator)

lazy val ledger_events = project

lazy val ledger_processor = project
  .dependsOn(ledger_events)

lazy val ledger_projection = project
  .dependsOn(ledger_events)

lazy val simulator = project
  .dependsOn(ledger_processor, ledger_events)
