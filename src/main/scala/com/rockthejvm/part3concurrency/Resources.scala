package com.rockthejvm.part3concurrency

import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import com.rockthejvm.utils._
import cats.effect.{IO, IOApp, Resource}

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
      .bracket(_.open *> IO.never)(
        _.close.void
      ) // IO.never means never terminates

  private val bracketProgram =
    for {
      fib <- bracketFetchUrl.start
      // when cancelled, the release callback will be called
      _ <- IO.sleep(1.second) *> fib.cancel
    } yield ()

  /** Exercises */

  /* 1. Open a file with text to print all lines, one every 100 millis, and then close the file */
  private def openFileScanner(path: String): IO[Scanner] =
    IO(s"opening file $path").debug >>
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
      IO(scanner.close()) >>
        IO("closed scanner").debug.void

  private def bracketReadFile(path: String): IO[Unit] =
    openFileScanner(path)
      .bracket(useOpenFileScannerToReadFile)(closeScannerAfterRead)

  /** Resources
    *
    * Because _nesting_ brackets (if using multiple resources)
    *  can become very hard to read
    */
  private val connectionResource =
    Resource.make(
      IO(Connection("rockthejvm.com")) // acquisition
    )(_.close.void) // release

  // usage happens at a later time
  private val resourceFetchUrl =
    for {
      fib <- connectionResource.use(_.open >> IO.never).start
      _ <- IO.sleep(1.second)
      _ <- fib.cancel
    } yield ()

  /* Resources are equivalent to brackets */
  private val simpleResource =
    IO("some resource")
  private val usingResource: String => IO[String] =
    str => IO(s"using the string: $str").debug
  private val releaseResource: String => IO[Unit] =
    str => IO(s"finalizing the string: $str").debug.void

  private val usingResourceWithBracket =
    simpleResource.bracket(usingResource)(releaseResource)
  private val usingResourceWithResource =
    Resource
      .make(simpleResource)(releaseResource)
      .use(usingResource)

  /** Exercises */

  /* Read a file using Resource */
  private def bracketReadFileResource(path: String): IO[Unit] =
    Resource
      .make(openFileScanner(path))(closeScannerAfterRead)
      .use(useOpenFileScannerToReadFile)

  /** Nested resources */

  private def connectionFromConfResource(
      path: String
  ): Resource[IO, Connection] =
    for {
      scanner <-
        Resource.make(openFileScanner(path))(closeScannerAfterRead)
      connection <-
        Resource.make(
          IO(Connection(scanner.nextLine()))
        )(_.close.void)
    } yield connection

  private val openConnection =
    connectionFromConfResource(
      getClass.getResource("/connection").getPath
    ).use(_.open >> IO.never)

  private val cancelledConnection =
    for {
      fib <- openConnection.start
      _ <-
        IO.sleep(1.second) >>
          IO("cancelling").debug >>
          fib.cancel
    } yield ()

  /* ^ All of this (file + connection) will close automatically ^ */

  /** Finalizers */

  /*
   * You can add finalizers to regular IOs for when an IO completes, errors, or cancels
   * using the `guarantee` method
   */
  private val ioWithFinalizer =
    IO("some resource").debug
      .guarantee(
        IO("freeing resource").debug.void
      )

  // .guaranteeCase lets you match on Outcome
  private val ioWithFinalizerV2 =
    IO("some resource").debug
      .guaranteeCase {
        case Succeeded(fa) =>
          for {
            a <- fa
            _ <- IO(s"releasing resource: $a").debug
          } yield ()
        case Errored(_) =>
          IO("nothing to release").debug.void
        case Canceled() =>
          IO("resource was cancelled, releasing what's left").debug.void
      }

  override def run: IO[Unit] = ioWithFinalizer.void
}
