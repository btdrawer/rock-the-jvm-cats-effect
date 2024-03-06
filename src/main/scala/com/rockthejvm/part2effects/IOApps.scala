package com.rockthejvm.part2effects

import cats.effect.{ExitCode, IO, IOApp}

import scala.io.StdIn

object IOApps {
  val program: IO[Unit] = for {
    line <- IO(StdIn.readLine())
    _ <- IO(println(s"echo: $line"))
  } yield ()
}

object FirstCatsEffectApp extends IOApp {
  import IOApps._

  // Required method: `run`
  override def run(args: List[String]): IO[ExitCode] =
    program.as(ExitCode.Success)
}

object MySimpleApp extends IOApp.Simple {
  import IOApps._

  // run with no arguments, and does not return ExitCode
  override def run: IO[Unit] = program
}
