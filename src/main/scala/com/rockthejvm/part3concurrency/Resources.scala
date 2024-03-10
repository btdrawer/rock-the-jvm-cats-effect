package com.rockthejvm.part3concurrency

import com.rockthejvm.utils._
import cats.effect.{IO, IOApp}

import java.io.{File, FileReader}
import java.util.Scanner
import scala.concurrent.duration.DurationInt

object Resources extends IOApp.Simple {

  // Use-case: Manage a connection lifecycle
  private final case class Connection(url: String) {
    def open: IO[String] = IO(s"opening connection to $url").debug
    def close: IO[String] = IO(s"closing connection to $url").debug
  }

  private val leakingAsyncFetchUrl = for {
    fib <-
      (Connection("rockthejvm.com").open *>
        IO.sleep(Int.MaxValue.seconds)).start
    _ <-
      IO.sleep(1.second) *>
        fib.cancel
  } yield ()

  // How do we avoid _leaking_ resources?

  private val correctAsyncFetchUrl = for {
    conn <- IO(Connection("rockthejvm.com"))
    fib <-
      // Use onCancel to close the connection
      (conn.open *>
        IO.sleep(Int.MaxValue.seconds))
        .onCancel(conn.close.void)
        .start
    _ <-
      IO.sleep(1.second) *>
        fib.cancel
  } yield ()

  /** Easier way to do this: the BRACKET PATTERN */

  /*
   * Resource acquisition and releasing
   *
   * Calling .bracket:
   * 1. First argument: A function that specifies what you can do with that effect
   * 2. Second argument: What to do with your resource when you want to release it
   *
   * This is a better approach because it FORCES you to think about
   * how you will release your resource
   */
  private val bracketFetchUrl =
    IO(Connection("rockthejvm.com"))
      .bracket(_.open *> IO.sleep(Int.MaxValue.seconds))(_.close.void)

  private val bracketProgram =
    for {
      fib <- bracketFetchUrl.start
      // when cancelled, the release callback will be called
      _ <- IO.sleep(1.second) *> fib.cancel
    } yield ()

  /** Exercises */

  /* 1. Open a file with text to print all lines, one every 100 millis, and then close the file */
  private def openFileScanner(path: String): IO[Scanner] =
    IO(
      new Scanner(
        new FileReader(new File(path))
      )
    )

  private val useOpenFileScannerToReadFile: Scanner => IO[Unit] =
    scanner =>
      for {
        lineOpt <-
          IO(scanner.nextLine())
            .redeem(_ => None, Some(_))
        _ <-
          lineOpt match {
            case Some(line) =>
              for {
                _ <- IO(println(line))
                _ <- IO.sleep(100.millis)
                _ <- useOpenFileScannerToReadFile(scanner)
              } yield ()

            case None =>
              IO.pure(())
          }
      } yield ()

  private val closeScannerAfterRead: Scanner => IO[Unit] =
    scanner =>
      IO(scanner.close()) *>
        IO(println("closed scanner"))

  private def bracketReadFile(path: String): IO[Unit] =
    openFileScanner(path)
      .bracket(useOpenFileScannerToReadFile)(closeScannerAfterRead)

  override def run: IO[Unit] =
    bracketReadFile(
      getClass.getResource("/hello-world").getPath
    )
}
