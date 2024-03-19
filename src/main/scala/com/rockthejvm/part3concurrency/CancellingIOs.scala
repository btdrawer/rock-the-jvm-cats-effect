package com.rockthejvm.part3concurrency

import com.rockthejvm.utils._
import cats.effect.{IO, IOApp}

import scala.concurrent.duration.DurationInt

object CancellingIOs extends IOApp.Simple {
  /*
   * Cancelling IOs:
   * - fib.cancel
   * - IO.race and other APIs that cancel automatically
   * - Manual cancellation (this is new)
   */

  // Manual cancellation:
  private val chainOfIOs: IO[Int] =
    IO("waiting").debug >>
      IO.canceled >>
      IO(20).debug
  // ^ does not throw

  /*
   * Uncancelable
   *
   * e.g.: Payment processor in an online store -
   * should NEVER be cancelled.
   */
  private val specialPaymentSystem =
    (
      IO("Payment running, don't cancel me").debug >>
        IO.sleep(1.second) >>
        IO("Payment complete").debug
    ).onCancel(
      IO("HUGE incident!!!").debug.void
    )

  // This is bad
  private val cancellationOfDoom =
    for {
      fib <- specialPaymentSystem.start
      _ <- IO.sleep(500.millis) >> fib.cancel
      _ <- fib.join
    } yield ()

  /*
   * This is called 'masking' - the effect is 'masked'
   * by IO.uncancelable.
   */
  private val atomicPayment =
    IO.uncancelable(_ => specialPaymentSystem)
  // syntax sugar
  private val atomicPaymentV2 =
    specialPaymentSystem.uncancelable

  // The 'cancel' here will not work
  private val noCancellationOfDoom =
    for {
      fib <- atomicPaymentV2.start
      _ <-
        IO.sleep(500.millis) >>
          IO("attempting cancellation") >>
          fib.cancel
      _ <- fib.join
    } yield ()

  /*
   * The IO.uncancelable function provides a `Poll[IO]` as an argument,
   * and this allows you to mark certain effects within the function
   * as cancelable.
   *
   * e.g.: Authentication service, with 2 parts:
   * 1. Input password - can be cancelled
   * 2. Verify password - CANNOT be cancelled
   *
   * Cancel entire flow if (1) not completed in one minute,
   * else we will block indefinitely on user input
   */
  private val inputPassword =
    IO("input password").debug >>
      IO("(typing password)").debug >>
      IO.sleep(5.seconds) >>
      IO("password1!")

  private val verifyPassword = (pw: String) =>
    IO("verifying...").debug >>
      IO.sleep(2.seconds) >>
      IO(pw == "password1!")

  private val authFlow: IO[Unit] =
    IO.uncancelable { poll =>
      for {
        pw <- poll(inputPassword) // unmasking `inputPassword`
          .onCancel(
            IO("Authentication timed out").debug.void
          )
        verified <- verifyPassword(pw)
        _ <-
          if (verified) IO("Authenticated").debug
          else IO("Authentication failed").debug
      } yield ()
    }

  private val authProgram =
    for {
      authFib <- authFlow.start
      _ <-
        IO.sleep(3.seconds) >>
          IO("Authentication timeout, attempting cancel...").debug >>
          authFib.cancel
      _ <- authFib.join
    } yield ()

  /** Exercises */

  /* What will happen if we run these effects? */

  // 1.
  private val uncancelableNumber =
    IO.uncancelable(_ => IO.canceled >> IO(20).debug)

  /* '20' will be printed - you can't cancel an uncancelable */

  // 2.
  private val invincibleAuthProgram =
    for {
      /*
       * In the original authProgram,
       * the password input is wrapped in a poll (i.e. is cancelable).
       */
      authFib <- IO.uncancelable(_ => authFlow).start
      _ <-
        IO.sleep(3.seconds) >>
          IO("Authentication timeout, attempting cancel...").debug >>
          authFib.cancel
      _ <- authFib.join
    } yield ()

  /* The inner poll will be ignored - so this won't be cancelable */

  /*
   * N.B. `IO.uncancelable` and `poll` cancel each other out
   *
   * e.g.:
   *  IO.uncancelable(poll => poll(authFlow)).start
   * Is the same as:
   *  authFlow.start
   */

  // 3.
  private def threeStepProgram: IO[Unit] = {
    val sequence =
      IO.uncancelable { poll =>
        poll(
          IO("cancelable").debug >> IO.sleep(1.second) >> IO(
            "cancelable end"
          ).debug
        ) >>
          IO("uncancelable").debug >>
          IO.sleep(1.second) >>
          IO("uncancelable end").debug >>
          poll(
            IO("second cancelable").debug >> IO.sleep(1.second) >> IO(
              "second cancelable end"
            ).debug
          )
      }

    for {
      fib <- sequence.start
      _ <-
        IO.sleep(1500.millis) >>
          IO("CANCELING").debug >>
          fib.cancel
      _ <- fib.join
    } yield ()
  }

  /*
   * I am not sure whether .cancel sends a one-off signal, or if that
   * cancelation then persists
   *
   * The latter seems more likely to me,
   * so I think the 'second cancelable' will not ever be printed.
   *
   * (this was correct)
   */

  override def run: IO[Unit] = threeStepProgram
}
