package com.rockthejvm.part3concurrency

import cats.effect.kernel.Outcome
import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import com.rockthejvm.utils._
import cats.effect.{Fiber, IO, IOApp}
import cats.implicits.catsSyntaxEitherId

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object RacingIOs extends IOApp.Simple {
  private def runWithSleep[A](value: A, duration: FiniteDuration): IO[A] =
    (IO(s"starting computation: $value").debug >>
      IO.sleep(duration) >>
      IO(s"computation for $value done") >>
      IO(value))
      .onCancel(
        IO(s"computation cancelled for $value").debug.void
      )

  private def testRace: IO[String] = {
    val aNumber = runWithSleep(20, 1.second)
    val aString = runWithSleep("string", 2.seconds)

    /*
     * The `race` method:
     * - Both IOs run on different fibers
     * - The winner will be returned (Left or Right)
     * - The loser will be cancelled
     *
     * Useful because it means we're handling fibers
     * and cancelling automatically
     */
    val first: IO[Either[Int, String]] = IO.race(aNumber, aString)

    first.flatMap {
      case Left(num)  => IO(s"aNumber won: $num")
      case Right(str) => IO(s"aString won: $str")
    }
  }

  private def testRacePair = {
    val aNumber = runWithSleep(20, 1.second)
    val aString = runWithSleep("string", 2.seconds)

    /*
     * racePair:
     * Gives you more control than race.
     * You receive the Outcome of the winner and the Fiber of the loser,
     * so you can handle both manually.
     */
    val raceResult: IO[Either[
      (Outcome[IO, Throwable, Int], Fiber[IO, Throwable, String]),
      (Fiber[IO, Throwable, Int], Outcome[IO, Throwable, String])
    ]] =
      IO.racePair(aNumber, aString)

    raceResult.flatMap {
      case Left((outcomeNum, fiberStr)) =>
        fiberStr.cancel >>
          IO("aNumber won").debug >>
          IO(outcomeNum).debug
      case Right((fiberNum, outcomeStr)) =>
        fiberNum.cancel >>
          IO("aString won").debug >>
          IO(outcomeStr).debug
    }
  }

  /** Exercises */

  /* 1. Timeout pattern with race */
  private def timeout[A](ioa: IO[A], duration: FiniteDuration): IO[A] =
    IO.race(ioa, IO.sleep(duration))
      .flatMap {
        case Left(a) =>
          IO.pure(a)
        case Right(_) =>
          IO.raiseError[A](
            new RuntimeException("The operation timed out.")
          )
      }

  /* 2. Unrace - return the loser */
  private def unrace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    IO.racePair(ioa, iob)
      .flatMap {
        case Left((_, fib)) =>
          handleFiber(fib).map(_.asRight[A])
        case Right((fib, _)) =>
          handleFiber(fib).map(_.asLeft[B])
      }

  private def handleFiber[A](fib: Fiber[IO, Throwable, A]): IO[A] =
    for {
      outcome <- fib.join
      result <- handleOutcome(outcome)
    } yield result

  private def handleOutcome[A](outcome: Outcome[IO, Throwable, A]): IO[A] =
    outcome match {
      case Succeeded(fa) =>
        fa
      case Errored(e) =>
        IO.raiseError[A](e)
      case Canceled() =>
        IO.raiseError[A](new RuntimeException("The fiber was cancelled"))
    }

  /* 3. Simple race */

  /*
   * INCORRECT - also need to cancel the still-running fiber
   * and also, if the other fiber gets cancelled, return the one still running
   */
  private def simpleRace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    IO.racePair(ioa, iob)
      .flatMap {
        case Left((outcome, _)) =>
          handleOutcome(outcome).map(_.asLeft[B])
        case Right((_, outcome)) =>
          handleOutcome(outcome).map(_.asRight[A])
      }

  private def correctSimpleRace[A, B](
      ioa: IO[A],
      iob: IO[B]
  ): IO[Either[A, B]] =
    IO.racePair(ioa, iob)
      .flatMap {
        case Left((outA, fibB)) =>
          outA match {
            case Succeeded(a) =>
              fibB.cancel >> a.map(_.asLeft[B])
            case Errored(e) =>
              fibB.cancel >> IO.raiseError[Either[A, B]](e)
            case Canceled() =>
              handleFiber(fibB).map(_.asRight[A])
          }
        case Right((fibA, outB)) =>
          outB match {
            case Succeeded(b) =>
              fibA.cancel >> b.map(_.asRight[A])
            case Errored(e) =>
              fibA.cancel >> IO.raiseError[Either[A, B]](e)
            case Canceled() =>
              handleFiber(fibA).map(_.asLeft[B])
          }
      }

  override def run: IO[Unit] = testRacePair.debug.void
}
