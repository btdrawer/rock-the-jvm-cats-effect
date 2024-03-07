package com.rockthejvm.part2effects

import cats.Parallel
import cats.effect.{IO, IOApp}

object IOParallelism extends IOApp.Simple {

  /** Sequential IOs */
  private val aniIO = IO(
    s"[${Thread.currentThread().getName}] Ani"
  )
  private val kamranIO = IO(
    s"[${Thread.currentThread().getName}] Kamran"
  )

  private val composedIO = for {
    ani <- aniIO
    kamran <- kamranIO
  } yield s"$ani and $kamran"

  import com.rockthejvm.utils._
  import cats.syntax.apply._
  private val aNumber: IO[Int] = IO(20)
  private val aString: IO[String] = IO("a string")
  // Here, aNumber and aString will be evaluated on the same thread
  private val numberAndString =
    (aNumber.debug, aString.debug)
      .mapN((num, str) => s"$num + $str")

  /** Parallelism */
  // Convert sequential to parallel
  import cats.effect.implicits._
  private val aNumberPar: IO.Par[Int] = Parallel[IO].parallel(aNumber.debug)
  private val aStringPar: IO.Par[String] = Parallel[IO].parallel(aString.debug)
  // Here, aNumberPar and aStringPar will be evaluated on different threads
  private val numberAndStringPar: IO.Par[String] =
    (aNumberPar, aStringPar).mapN((num, str) => s"$num + $str")
  // Convert back to sequential
  private val backToSequential: IO[String] =
    Parallel[IO].sequential(numberAndStringPar)

  // Synchronization happens AUTOMATICALLY!

  // Shorthand:
  import cats.syntax.parallel._
  // Same as `backToSequential`
  private val numberAndStringV3: IO[String] =
    (aNumber.debug, aString.debug)
      .parMapN((num, str) => s"$num + $str")

  /* Failure handling */
  private val aFailure: IO[String] =
    IO.raiseError(new RuntimeException("I can't do this"))

  // compose success + failure
  private val parallelWithFailure =
    (aFailure.debug, aString.debug).parMapN(_ + _)
  /*
   * output:
   * aString still executes, but an exception is thrown
   * and so the final concatenation does not happen
   *
   * you can handle errors more gracefully using redeemWith, etc.
   */

  // compose failure + failure
  private val anotherFailure: IO[String] = IO.raiseError(
    new RuntimeException("second failure")
  )
  private val twoFailures: IO[String] =
    (aFailure.debug, anotherFailure.debug).parMapN(_ + _)
  // output: Whichever exception is thrown first kills the app

  override def run: IO[Unit] = twoFailures.debug.void
}
