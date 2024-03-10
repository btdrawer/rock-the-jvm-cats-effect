package com.rockthejvm.part3concurrency

import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import com.rockthejvm.utils._
import cats.effect.{Fiber, IO, IOApp, Outcome}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object Fibers extends IOApp.Simple {
  private val aNumber = IO.pure(30)
  private val aString = IO.pure("string")

  // IOs run sequentially -> on the same thread
  private def sameThreadIOs: IO[Unit] =
    for {
      _ <- aNumber.debug
      _ <- aString.debug
    } yield ()

  /*
   * Fibers:
   * A computation that will be scheduled on a thread
   * managed by the cats-effect IO runtime.
   *
   * It's almost impossible to create Fibers yourself -
   * you can use the cats-effect API
   */
  private def createFiber: Fiber[IO, Throwable, String] = ???

  /*
   * Use .start to create a fiber
   * It is wrapped in an IO because fiber creation is effectful
   */
  private val aFiber: IO[Fiber[IO, Throwable, Int]] =
    aNumber.debug.start

  // will be run on different threads
  private def differentThreadIOs: IO[Unit] =
    for {
      _ <- aFiber
      _ <- aString.debug
    } yield ()

  // manage a fiber's lifecycle by joining it - ie waiting for a fiber to finish
  private def runOnSomeOtherThread[A](
      ioa: IO[A]
  ): IO[Outcome[IO, Throwable, A]] =
    for {
      fib <- ioa.start // Fiber[IO, Throwable, A]
      /*
       * waits for the fiber to terminate
       *
       * Returns Outcome[IO, Throwable, A].
       * There are 3 possible Outcomes:
       * - Succeeded
       * - Errored
       * - Canceled
       */
      result <- fib.join
    } yield result

  private val someIOOnAnotherThread =
    runOnSomeOtherThread(aNumber)
  // Handling Outcomes
  private val someResultFromAnotherThread =
    someIOOnAnotherThread.flatMap {
      case Succeeded(fa)           => fa
      case Errored(_) | Canceled() => IO.pure(0)
    }

  // Example Errored outcome
  private def throwOnAnotherThread: IO[Outcome[IO, Throwable, Int]] =
    for {
      fib <-
        IO
          .raiseError[Int](
            new RuntimeException("no number!")
          )
          .start
      result <- fib.join
    } yield result

  // Example Canceled outcome
  private def testCancel: IO[Outcome[IO, Throwable, String]] = {
    val task =
      IO("starting").debug >>
        IO.sleep(1.second) >>
        IO("done").debug

    /*
     * You can handle Canceled using the `onCancel` finalizer method
     * Requires an IO[Unit] which will be run on cancellation
     *
     * This is useful if your fiber has open resources that you
     * need to close
     */
    val taskWithCancellationHandler =
      task.onCancel(
        IO("I'm being cancelled").debug.void
      )

    for {
      fib <- taskWithCancellationHandler.start
      _ <-
        IO.sleep(500.millis) >>
          IO("cancelling").debug
      _ <- fib.cancel
      result <- fib.join
    } yield result
  }

  /** Exercises */

  /*
   * 1. Write a function that runs an IO on another thread,
   *  depending on the result of the fiber
   *  - return the result in an IO
   *  - if errored or canceled, return a failed IO
   */
  private def processResultsFromFiber[A](ioa: IO[A]): IO[A] =
    for {
      fib <- ioa.start
      outcome <- fib.join
      result <-
        outcome match {
          case Succeeded(fa) =>
            fa
          case Errored(e) =>
            IO.raiseError[A](e)
          case Canceled() =>
            IO.raiseError[A](new RuntimeException("The fiber was cancelled"))
        }
    } yield result

  private def testEx1 = {
    val anIO =
      IO("starting").debug >>
        IO.sleep(1.second) >>
        IO("done").debug >>
        IO(30)

    processResultsFromFiber(anIO).void
  }

  /*
   * 2. A function that takes 2 IOs, runs them on different fibers,
   *  and returns an IO with a tuple containing both results.
   *  - If the first IO returns an error, raise the error
   *  - If the first IO doesn't error but the second one does, raise that
   *  - If at least one IO gets canceled, raise a RuntimeException
   */
  private def tupleIOs[A, B](ioa: IO[A], iob: IO[B]): IO[(A, B)] =
    for {
      fibA <- ioa.start
      fibB <- iob.start

      outcomeA <- fibA.join
      outcomeB <- fibB.join

      result <- (outcomeA, outcomeB) match {
        case (Succeeded(fa), Succeeded(fb)) =>
          for {
            a <- fa
            b <- fb
          } yield (a, b)

        case (Errored(e), _) =>
          IO.raiseError[(A, B)](e)

        case (_, Errored(e)) =>
          IO.raiseError[(A, B)](e)

        case (Canceled(), _) | (_, Canceled()) =>
          IO.raiseError[(A, B)](
            new RuntimeException("One or both fibers were cancelled")
          )
        // ^ Should add default case (still works though)
      }
    } yield result

  private def testEx2 = {
    val firstIO = IO.sleep(2.seconds) >> IO(1).debug
    val secondIO = IO.sleep(3.seconds) >> IO(2).debug
    tupleIOs(firstIO, secondIO).debug.void
  }

  /*
   * 3. A function that adds a timeout to an IO:
   *  - IO runs on a fiber
   *  - If the timeout duration passes, the fiber is canceled
   */
  private def timeout[A](ioa: IO[A], duration: FiniteDuration): IO[A] =
    for {
      fib <- ioa.start
      /*
       * Problem with this approach: It means we _wait_ for the full timeout
       * Need to call .start, e.g.:
       *    _ <- (IO.sleep(duration) >> fib.cancel).start
       * BUT fibers can leak - this esp matters when you're doing a heavy computation
       */
      _ <-
        IO.sleep(duration) >>
          fib.cancel
      outcome <- fib.join
      result <-
        outcome match {
          case Succeeded(fa) =>
            fa
          case Errored(e) =>
            IO.raiseError[A](e)
          case Canceled() =>
            IO.raiseError[A](
              new RuntimeException("The fiber was canceled")
            )
        }
    } yield result

  private def testEx3 = {
    val anIO =
      IO("starting").debug >>
        IO.sleep(1.second) >>
        IO("done").debug >>
        IO(30)

    timeout(anIO, 200.millis).debug.void
  }

  override def run: IO[Unit] =
    testEx3
}
