package com.rockthejvm.part5polymorphic

import cats.effect.kernel.{Async, Outcome}
import cats.effect._
import cats.effect.implicits._
import cats.effect.std.Queue
import com.rockthejvm.part4coordination.State.Signal
import com.rockthejvm.part4coordination.{Mutex, State}
import com.rockthejvm.utils.general.DebugWrapper

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object PolymorphicCoordination extends IOApp.Simple {
  // concurrent - Ref + Deferred for ANY effect type
  trait MyConcurrent[F[_]] extends Spawn[F] {
    def ref[A](a: A): F[Ref[F, A]]
    def deferred[A]: F[Deferred[F, A]]
  }

  private val concurrentIO = Concurrent[IO]

  import cats.implicits._
  import cats.effect.syntax.spawn._

  private def unsafeSleep[F[_], E](duration: FiniteDuration)(implicit
      MC: MonadCancel[F, E]
  ): F[Unit] =
    Thread.sleep(duration.toMillis).pure[F]

  /*
   * Generalising some earlier code
   * (Which I had already generalised, but not as much as is possible)
   */
  private def timer[F[_]](seconds: Int)(implicit C: Concurrent[F]): F[Unit] = {
    def counter(count: Ref[F, Int], signal: Deferred[F, Unit]): F[Unit] =
      for {
        _ <- unsafeSleep(1.second)
        latest <- count.updateAndGet(_ + 1)
        _ <-
          if (latest >= seconds) signal.complete(()).void
          else counter(count, signal)
      } yield ()

    def notifier(signal: Deferred[F, Unit]): F[Unit] =
      for {
        _ <- s"Setting a timer for $seconds seconds...".pure[F].debug
        _ <- signal.get
        _ <- "Time's up!".pure[F].debug
      } yield ()

    for {
      count <- C.ref(0)
      signal <- C.deferred[Unit]

      notifierFib <- notifier(signal).start
      counterFib <- counter(count, signal).start

      _ <- notifierFib.join
      _ <- counterFib.join
    } yield ()
  }

  /** Exercises */

  /*
   * 1. Implement racePair
   *    I had generalised this to Async already, generalise now to Concurrent
   */
  private type RaceResult[F[_], A, B] =
    Either[
      (Outcome[F, Throwable, A], Fiber[F, Throwable, B]),
      (Fiber[F, Throwable, A], Outcome[F, Throwable, B])
    ]

  private type EitherOutcome[F[_], A, B] =
    Either[Outcome[F, Throwable, A], Outcome[F, Throwable, B]]

  private def racePair[F[_], A, B](
      fa: F[A],
      fb: F[B]
  )(implicit C: Concurrent[F]): F[RaceResult[F, A, B]] =
    C.uncancelable { poll =>
      for {
        signal <- Deferred[F, EitherOutcome[F, A, B]]
        fibA <-
          fa.guaranteeCase(outcome =>
            signal.complete(outcome.asLeft[Outcome[F, Throwable, B]]).void
          ).start
        fibB <-
          fb.guaranteeCase(outcome =>
            signal.complete(outcome.asRight[Outcome[F, Throwable, A]]).void
          ).start
        // blocking call -> should be cancelable
        outcome <- poll(signal.get).onCancel {
          // cancel other effects when cancelled
          for {
            cancelFibA <- fibA.cancel.start
            cancelFibB <- fibB.cancel.start
            _ <- cancelFibA.join
            _ <- cancelFibB.join
          } yield ()
        }
        result = outcome.map(fibA -> _).leftMap(_ -> fibB)
      } yield result
    }

  /* 2. Generalize Mutex */

  // Again - had already done this, but with Async
  private object GenMutex {
    def create[F[_]](implicit C: Concurrent[F]): F[Mutex[F]] =
      for {
        queue <- Queue.unbounded[F, Signal[F]]
        unlocked = State(locked = false, queue)
        ref <- Ref[F].of(unlocked)
      } yield new Mutex[F] {
        override def acquire: F[Unit] =
          C.uncancelable { poll =>
            ref.modify {
              case State(false, waiting) =>
                State(locked = true, waiting) -> C.unit

              case state =>
                state -> addSignalToQueueAndWait(state.waiting, poll)
            }.flatten
          }

        private def addSignalToQueueAndWait(
            waiting: Queue[F, Signal[F]],
            poll: Poll[F]
        ): F[Unit] =
          for {
            signal <- Deferred[F, Unit]
            _ <- waiting.offer(signal)
            // `signal.get` is the one thing that should be cancelable because it does not affect lock state
            _ <- poll(signal.get).onCancel(releaseCancelledSignal(signal))
          } yield ()

        /*
         * This solution is different from the video, again because of my use of a cats-effect poll, meaning
         * we cannot simply filter out the signal we want.
         * Instead, because we don't know where in the queue our signal is, we have to complete the signal here.
         */
        private def releaseCancelledSignal(
            signal: Signal[F]
        ): F[Unit] =
          for {
            _ <- signal.complete(())
            state <- ref.get
            _ <- if (state.locked) C.unit else release
          } yield ()

        /*
         * The whole release should be uncancelable because we want the lock to be available
         * to other processes after the fiber is cancelled
         *
         * (The data may not be in a good state, but the alternative would be to have the lock
         * permanently held)
         *
         * MINOR CORRECTION FROM VIDEO: Correct that we don't want release to be cancelable, but this is, in fact,
         * _already_ the case as `modify` is atomic - so the A.uncancelable wrapper is redundant.
         */
        override def release: F[Unit] =
          C.uncancelable { _ =>
            ref.modify {
              case state if !state.locked =>
                state -> C.unit

              case state @ State(_, waiting) =>
                state -> releaseSignalOrUnlock(waiting)
            }.flatten
          }

        private def releaseSignalOrUnlock(
            waiting: Queue[F, Signal[F]]
        ): F[Unit] =
          for {
            signalOpt <- waiting.tryTake
            _ <-
              signalOpt match {
                case Some(signal) => releaseSignal(signal)
                case None         => unlock
              }
          } yield ()

        private def releaseSignal(
            signal: Signal[F]
        ): F[Unit] =
          for {
            justCompleted <- signal.complete(())
            _ <- if (justCompleted) C.unit else release
          } yield ()

        private def unlock: F[Unit] = ref.update(_.copy(locked = false))
      }
  }

  override def run: IO[Unit] = timer[IO](5)
}
