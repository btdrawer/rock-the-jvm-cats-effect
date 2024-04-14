package com.rockthejvm.part5polymorphic

import cats.effect.implicits.genSpawnOps
import cats.effect.kernel.GenConcurrent
import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.{Applicative, Monad}
import cats.effect.{IO, IOApp, MonadCancel, Poll}
import cats.syntax.all._
import com.rockthejvm.utils.general.DebugWrapper

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object PolymorphicCancellation extends IOApp.Simple {
  // ApplicativeError
  trait MyApplicativeError[F[_], E] extends Applicative[F] {
    def raiseError[A](error: E): F[A]
    def handleErrorWith[A](fa: F[A])(f: E => F[A]): F[A]
  }

  // MonadError
  trait MyMonadError[F[_], E] extends MyApplicativeError[F, E] with Monad[F]

  // MonadCancel
  trait MyMonadCancel[F[_], E] extends MyMonadError[F, E] {
    def canceled: F[Unit] // cancels the chain
    def uncancelable[A](poll: Poll[F] => F[A]): F[A]
  }

  trait MyPoll[F[_]] {
    def apply[A](fa: F[A]): F[A]
  }

  // MonadCancel for IO
  private val monadCancelIO: MonadCancel[IO, Throwable] = MonadCancel[IO]

  // we can create values because MonadCancel is a monad
  private val aNumberIO: IO[Int] =
    monadCancelIO.pure(20)
  private val ambitiousNumberIO: IO[Int] =
    monadCancelIO.map(aNumberIO)(_ * 10)

  private val mustComputeIO =
    monadCancelIO.uncancelable { _ =>
      for {
        _ <- monadCancelIO.pure("once started, I cannot go back")
        res <- monadCancelIO.pure(56)
      } yield res
    }

  // allows us to generalise effects
  private def mustCompute[F[_], E](implicit MC: MonadCancel[F, E]): F[Int] =
    MC.uncancelable { _ =>
      for {
        _ <- MC.pure("once started, I cannot go back")
        res <- MC.pure(56)
      } yield res
    }

  private val mustComputeV2 = mustCompute[IO, Throwable]

  // allow cancellation listeners
  private val mustComputeWithListener =
    mustComputeV2.onCancel("I'm being cancelled".pure[IO].void)

  // onCancel as extension method
  import cats.effect.syntax.monadCancel._

  // allow finalizers
  private val aComputationWithFinalizers =
    monadCancelIO.guaranteeCase(42.pure[IO]) {
      case Succeeded(fa) => fa.flatMap(a => s"successful: $a".pure[IO].void)
      case Errored(e)    => s"failed: $e".pure[IO].void
      case Canceled()    => "canceled".pure[IO].void
    }

  // bracket pattern is specific to MonadCancel
  private val aComputationWithUsage =
    monadCancelIO.bracket(20.pure[IO]) { a =>
      s"using number: $a".pure[IO]
    } { a =>
      s"releasing value $a".pure[IO].void
    }

  /** Exercise: Generalise this code */
  private object IOCode {
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

    val authProgram: IO[Unit] =
      for {
        authFib <- authFlow.start
        _ <-
          IO.sleep(3.seconds) >>
            IO("Authentication timeout, attempting cancel...").debug >>
            authFib.cancel
        _ <- authFib.join
      } yield ()
  }

  private def unsafeSleep[F[_], E](duration: FiniteDuration)(implicit
      MC: MonadCancel[F, E]
  ): F[Unit] = MC.pure(Thread.sleep(duration.toMillis))

  private object GeneralisedSolution {
    private def inputPassword[F[_], E](implicit
        MC: MonadCancel[F, E]
    ): F[String] =
      for {
        _ <- "input password".pure[F].debug
        _ <- "(typing password)".pure[F].debug
        _ <- unsafeSleep(5.seconds)
        password <- "password1!".pure[F].debug
      } yield password

    private def verifyPassword[F[_], E](pw: String)(implicit
        MC: MonadCancel[F, E]
    ): F[Boolean] =
      for {
        _ <- "verifying...".pure[F].debug
        _ <- unsafeSleep(2.seconds)
        verified <- (pw == "password1!").pure[F]
      } yield verified

    private def authFlow[F[_], E](implicit MC: MonadCancel[F, E]): F[Unit] =
      MC.uncancelable { poll =>
        for {
          pw <- poll(inputPassword) // unmasking `inputPassword`
            .onCancel(
              "Authentication timed out".pure[F].debug.void
            )
          verified <- verifyPassword(pw)
          _ <-
            if (verified) "Authenticated".pure[F].debug
            else "Authentication failed".pure[F].debug
        } yield ()
      }

    def authProgram[F[_], E](implicit
        GC: GenConcurrent[F, E]
    ): F[Unit] =
      for {
        authFib <- authFlow.start
        _ <- unsafeSleep(3.seconds)
        _ <- "Authentication timeout, attempting cancel...".pure[F].debug
        _ <- authFib.cancel
        _ <- authFib.join
      } yield ()
  }

  override def run: IO[Unit] = GeneralisedSolution.authProgram[IO, Throwable]
}
