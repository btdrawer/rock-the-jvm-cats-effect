package com.rockthejvm.part5polymorphic

import cats.effect.{Concurrent, IO, IOApp, Temporal}
import cats.implicits._

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object PolymorphicTemporalSuspension extends IOApp.Simple {
  // Temporal - time-blocking effects
  trait MyTemporal[F[_]] extends Concurrent[F] {
    def sleep(duration: FiniteDuration): F[Unit] // semantic blocking
  }

  /*
   * Abilities:
   * - pure
   * - map/flatMap (Monad)
   * - raiseError (MonadError)
   * - uncancelable (MonadCancel)
   * - start (Spawn)
   * - ref/deferred (Concurrent)
   * - sleep (Temporal)
   */

  /** Exercises */

  /* 1. Generalise this code: */
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

  private def genTimeout[F[_], A](fa: F[A], duration: FiniteDuration)(implicit
      temporal: Temporal[F]
  ): F[A] =
    temporal
      .race(fa, temporal.sleep(duration))
      .flatMap {
        case Left(a) =>
          a.pure[F]
        case Right(_) =>
          new RuntimeException("The operation timed out.")
            .raiseError[F, A]
      }

  // Temporal in fact has a timeout method built in:
  private val temporalIO = Temporal[IO]
  private val timeoutF = temporalIO.timeout(temporalIO.unit, 500.millis)

  override def run: IO[Unit] = timeoutF
}
