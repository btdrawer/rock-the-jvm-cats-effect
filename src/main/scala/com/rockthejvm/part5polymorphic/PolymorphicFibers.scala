package com.rockthejvm.part5polymorphic

import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.kernel.{MonadCancel, Outcome}
import cats.effect.{Fiber, IO, IOApp, Spawn}
import cats.implicits._
import com.rockthejvm.utils.general.DebugWrapper

import scala.concurrent.duration.DurationInt

object PolymorphicFibers extends IOApp.Simple {
  // GenSpawn - create fibers for any effect
  // `Gen` implicits always have a generic error type
  trait MyGenSpawn[F[_], E] extends MonadCancel[F, E] {
    def start[A](fa: F[A]): F[Fiber[F, Throwable, A]]
    def never[A]: F[A] // a never-suspending effect
    def cede: F[Unit] // a `yield` effect - switch the thread

    def racePair[A, B](fa: F[A], fb: F[B]): F[
      Either[
        (Outcome[F, E, A], Fiber[F, E, B]),
        (Fiber[F, E, A], Outcome[F, E, B])
      ]
    ]
  }

  // Spawn - GenSpawn with error as any Throwable
  trait MySpawn[F[_]] extends MyGenSpawn[F, Throwable]

  private val spawnIO = Spawn[IO]

  private def ioOnSomeThread[A](io: IO[A]): IO[Outcome[IO, Throwable, A]] =
    for {
      fib <- spawnIO.start(io)
      res <- fib.join
    } yield res

  // generalise
  import cats.syntax.functor._ // map
  import cats.syntax.flatMap._ // flatMap
  private def effectOnSomeThread[F[_], A](
      fa: F[A]
  )(implicit S: Spawn[F]): F[Outcome[F, Throwable, A]] =
    for {
      fib <- S.start(fa)
      res <- fib.join
    } yield res

  private val aNumberOnFiber: IO[Outcome[IO, Throwable, Int]] =
    effectOnSomeThread(20.pure[IO])

  /** Exercise: generalize this code */
  private object IOSimpleRace {
    def apply[A, B](
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
  }

  private object GenSimpleRace {
    def apply[F[_], A, B](
        fa: F[A],
        fb: F[B]
    )(implicit S: Spawn[F]): F[Either[A, B]] =
      S.racePair(fa, fb)
        .flatMap {
          case Left((outA, fibB)) =>
            outA match {
              case Succeeded(a) =>
                fibB.cancel >> a.map(_.asLeft[B])
              case Errored(e) =>
                fibB.cancel >> e.raiseError[F, Either[A, B]]
              case Canceled() =>
                handleFiber(fibB).map(_.asRight[A])
            }
          case Right((fibA, outB)) =>
            outB match {
              case Succeeded(b) =>
                fibA.cancel >> b.map(_.asRight[A])
              case Errored(e) =>
                fibA.cancel >> e.raiseError[F, Either[A, B]]
              case Canceled() =>
                handleFiber(fibA).map(_.asLeft[B])
            }
        }

    private def handleFiber[F[_]: Spawn, A](
        fib: Fiber[F, Throwable, A]
    ): F[A] =
      for {
        outcome <- fib.join
        result <- handleOutcome(outcome)
      } yield result

    private def handleOutcome[F[_]: Spawn, A](
        outcome: Outcome[F, Throwable, A]
    ): F[A] =
      outcome match {
        case Succeeded(fa) =>
          fa
        case Errored(e) =>
          e.raiseError[F, A]
        case Canceled() =>
          new RuntimeException("The fiber was cancelled").raiseError[F, A]
      }
  }

  private val fast = IO.sleep(1.second) >> IO("fast").debug
  private val slow = IO.sleep(2.seconds) >> IO("slow").debug
  private val ioRace = IOSimpleRace(fast, slow)
  private val genRace = GenSimpleRace(fast, slow)

  override def run: IO[Unit] = genRace.void
}
