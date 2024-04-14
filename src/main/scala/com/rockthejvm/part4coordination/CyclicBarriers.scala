package com.rockthejvm.part4coordination

import cats.Parallel
import cats.effect.kernel.Async
import cats.effect.std.CyclicBarrier
import cats.effect.{Deferred, IO, IOApp}
import cats.implicits._
import com.rockthejvm.utils.DebugWrapper

import scala.concurrent.duration.DurationInt
import scala.util.Random

object CyclicBarriers extends IOApp.Simple {

  /** A cyclic barrier has a single method, `await`.
    *
    * It will semantically block all fibers calling `await` UNTIL
    * a certain number of fibers are calling `await`, at which point
    * it will release all of them.
    *
    * Any further fibers will AGAIN wait AGAIN, repeating the cycle.
    */

  /*
   * eg: Signing up for a social network that is about to be launched
   * Will only launch once we reach n users
   */
  private def createUser[F[_]](
      id: Int,
      barrier: CustomCyclicBarrier[F]
  )(implicit A: Async[F]): F[Unit] =
    for {
      _ <- A.sleep((Random.nextDouble * 500).toInt.millis)
      _ <- s"[user $id] signing up for the waitlist...".pure[F].debug
      _ <- A.sleep((Random.nextDouble * 1500).toInt.millis)
      _ <- s"[user $id] on the waitlist now".pure[F].debug
      _ <- barrier.await
      _ <- s"[user $id] I'm in!".pure[F].debug
    } yield ()

  private def openNetwork[F[_]](implicit A: Async[F], P: Parallel[F]): F[Unit] =
    for {
      _ <-
        s"[announcer] social network waitlist is open - opening when we have 10 users"
          .pure[F]
          .debug
      barrier <- CustomCyclicBarrier[F](10)
      _ <-
        (1 to 14).toList
          .parTraverse(createUser(_, barrier))
    } yield ()

  /** Exercise: Implement a CyclicBarrier */
  trait CustomCyclicBarrier[F[_]] {
    def await: F[Unit]
  }

  private object CustomCyclicBarrier {
    // why did I call this `count`? too confusing
    private final case class State[F[_]](count: Int, signal: Deferred[F, Unit])

    def apply[F[_]](
        count: Int
    )(implicit A: Async[F]): F[CustomCyclicBarrier[F]] =
      for {
        signal <- A.deferred[Unit]
        stateRef <- A.ref(State(0, signal))
      } yield new CustomCyclicBarrier[F] {
        override def await: F[Unit] =
          stateRef.modify {
            case State(counter, signal) if counter + 1 == count =>
              State(0, signal) ->
                (for {
                  newSignal <- A.deferred[Unit]
                  _ <- stateRef.update(_.copy(signal = newSignal))
                  _ <- signal.complete(())
                } yield ())

            // CORRECTION: I initially forgot to increment here, silly
            case State(count, signal) =>
              State(count + 1, signal) -> signal.get
            // CORRECTION: I also forgot to flatten! easy to overlook when using modify!
          }.flatten
      }
  }

  /*
   * Video solution
   * This approach is safer because the new signal is created
   * before we start the atomic update of the state
   */
  private object CustomCyclicBarrierSolution {
    private final case class State[F[_]](
        nWaiting: Int,
        signal: Deferred[F, Unit]
    )

    def apply[F[_]](
        count: Int
    )(implicit A: Async[F]): F[CustomCyclicBarrier[F]] =
      for {
        signal <- A.deferred[Unit]
        stateRef <- A.ref(State(0, signal))
      } yield new CustomCyclicBarrier[F] {
        override def await: F[Unit] =
          A.deferred[Unit]
            .flatMap { newSignal =>
              stateRef.modify {
                case State(1, signal) =>
                  State(count, newSignal) -> signal.complete(()).void

                case State(n, signal) =>
                  State(n - 1, signal) -> signal.get
              }
            }
            .flatten
      }
  }

  override def run: IO[Unit] = openNetwork[IO]
}
