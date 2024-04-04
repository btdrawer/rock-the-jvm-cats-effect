package com.rockthejvm.part4coordination

import cats.Parallel
import cats.effect.implicits.{genSpawnOps, monadCancelOps_}
import cats.effect.kernel.Async
import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.std.Queue
import cats.effect.{Deferred, IO, IOApp, Poll, Ref}
import cats.implicits._
import com.rockthejvm.utils.DebugWrapper

import scala.concurrent.duration.DurationInt
import scala.util.Random

abstract class Mutex[F[_]] {
  def acquire: F[Unit]
  def release: F[Unit]
}

/** Exercise: Create a Mutex class */
object Mutex {
  private sealed trait LockState
  private final case object Locked extends LockState
  private final case object Unlocked extends LockState

  def create[F[_]](implicit A: Async[F]): F[Mutex[F]] = {
    def buildClass(
        // better to use a case class rather than tuple, more descriptive
        lockRef: Ref[F, (Deferred[F, Unit], LockState)]
    ): Mutex[F] =
      new Mutex[F] {
        override def acquire: F[Unit] =
          lockRef.modify {
            case (signal, Unlocked)  => (signal -> Locked) -> A.unit
            case tuple @ (signal, _) => tuple -> (signal.get >> acquire)
          }.flatten

        override def release: F[Unit] =
          for {
            newSignal <- Deferred[F, Unit]
            _ <-
              lockRef.modify { case (signal, _) =>
                (newSignal -> Unlocked) -> signal.complete(())
              }.flatten
          } yield ()
      }

    for {
      signal <- Deferred[F, Unit]
      lockRef <- Ref[F].of[(Deferred[F, Unit], LockState)](signal -> Unlocked)
    } yield buildClass(lockRef)
  }
}

/** The above solution works, this one (under `createSimpleMutex`) uses the hints from the video,
  * and then `createMutexWithCancellation` is part 2 (cancelling).
  *
  * The full solution then given in the video uses a standard Scala Queue,
  * whereas here I used a cats-effect Queue.
  *
  * That means that the video solution is arguably not pure: Side effects are used on the left
  * side of a modify tuple, which is NOT wrapped in an effect. My usage here of the cats-effect
  * Queue means that this is probably a purer solution.
  */
object MutexV2 {
  private type Signal[F[_]] = Deferred[F, Unit]
  private final case class State[F[_]](
      locked: Boolean,
      waiting: Queue[F, Signal[F]]
  )

  def create[F[_]](implicit A: Async[F]): F[Mutex[F]] =
    for {
      queue <- Queue.unbounded[F, Signal[F]]
      unlocked = State(locked = false, queue)
      ref <- Ref[F].of(unlocked)
    } yield createMutexWithCancellation[F](ref)

  private def createSimpleMutex[F[_]](
      ref: Ref[F, State[F]]
  )(implicit A: Async[F]): Mutex[F] =
    new Mutex[F] {
      /*
       * Change the state of the Ref:
       * - If unlocked, state becomes (true, [])
       * - If locked, state becomes (true, queue + new signal) and wait on that signal
       */
      override def acquire: F[Unit] =
        ref.modify {
          case State(false, queue) =>
            State(locked = true, queue) -> A.unit

          case state =>
            state -> addSignalToQueueAndWait(state.waiting)
        }.flatten

      /*
       * Change the state of the Ref:
       * - If the mutex is unlocked, leave the state unchanged
       * - If locked,
       *    - If queue is empty, unlock the mutex
       *    - If queue is not empty, take signal out of queue and complete it
       */
      override def release: F[Unit] =
        ref.modify {
          case state if !state.locked =>
            state -> A.unit

          case state @ State(_, queue) =>
            state -> (for {
              signalOpt <- queue.tryTake
              _ <-
                signalOpt match {
                  case Some(signal) =>
                    signal.complete(()).void // I forgot the `void` originally!
                  case None =>
                    ref.update(_.copy(locked = false))
                }
            } yield ())
        }.flatten

      private def addSignalToQueueAndWait(queue: Queue[F, Signal[F]]): F[Unit] =
        for {
          signal <- Deferred[F, Unit]
          _ <- queue.offer(signal)
          _ <- signal.get
        } yield ()
    }

  /** Exercise 2: What if we cancel the fiber? What things should be cancellable?
    *
    * NB this doesn't always work - sometimes hangs, will try to fix later
    */
  private def createMutexWithCancellation[F[_]](
      ref: Ref[F, State[F]]
  )(implicit A: Async[F]): Mutex[F] =
    new Mutex[F] {
      override def acquire: F[Unit] =
        A.uncancelable { poll =>
          ref.modify {
            case State(false, queue) =>
              State(locked = true, queue) -> A.unit

            case state =>
              // See `addSignalToQueueAndWait` for one thing that should be cancelled
              state -> addSignalToQueueAndWait(state.waiting, poll)
          }.flatten
        }

      private def addSignalToQueueAndWait(
          queue: Queue[F, Signal[F]],
          poll: Poll[F]
      ): F[Unit] =
        for {
          signal <- Deferred[F, Unit]
          _ <- queue.offer(signal)
          /*
           * `signal.get` is the one thing that should be cancelable because it does not affect lock state
           *
           * This solution is different from the video, again because of my use of a cats-effect poll, meaning
           * we cannot simply filter out the signal we want.
           * Instead, because we don't know where in the queue our signal is, we have to complete the signal here,
           * and then the next time `release` is called, it will simply release _again_ if the signal it finds
           * has already been completed before.
           */
          _ <- poll(signal.get).onCancel(signal.complete(()).void)
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
        A.uncancelable { _ =>
          ref.modify {
            case state if !state.locked =>
              state -> A.unit

            case state @ State(_, queue) =>
              state -> (for {
                signalOpt <- queue.tryTake
                _ <-
                  signalOpt match {
                    case Some(signal) =>
                      for {
                        justCompleted <- signal.complete(())
                        _ <-
                          if (justCompleted) A.unit
                          else release
                      } yield ()
                    case None =>
                      ref.update(_.copy(locked = false))
                  }
              } yield ())
          }.flatten
        }
    }
}

object MutexPlayground extends IOApp.Simple {
  // generate random number after sleeping for 1s
  private def criticalTask[F[_]: Async]: F[Int] =
    Async[F].sleep(1.second) >>
      Async[F].delay(Random.nextInt(100))

  private def createNonLockingTask[F[_]](
      id: Int
  )(implicit A: Async[F]): F[Int] =
    for {
      _ <- A.delay(s"[task $id] working...").debug
      result <- criticalTask
      _ <- A.delay(s"[task $id] got result: $result").debug
    } yield result

  private def demoNonLockingTasks[F[_]: Async: Parallel]: F[List[Int]] =
    (1 to 10).toList.parTraverse[F, Int](createNonLockingTask[F])

  private def createLockingTask[F[_]](
      id: Int,
      mutex: Mutex[F]
  )(implicit A: Async[F]): F[Int] =
    for {
      _ <- A.delay(s"[task $id] waiting for lock...").debug
      // critical section
      _ <- mutex.acquire // blocks if mutex has been acquired
      _ <- A.delay(s"[task $id] working...").debug
      result <- criticalTask
      _ <- A.delay(s"[task $id] got result: $result").debug
      // critical section ends
      _ <- mutex.release
      _ <- A.delay(s"[task $id] lock released").debug
    } yield result

  // only 1 task should proceed at one time
  private def demoLockingTask[F[_]: Async: Parallel]: F[List[Int]] =
    for {
      mutex <- Mutex.create[F]
      results <-
        (1 to 10).toList.parTraverse[F, Int](
          createLockingTask[F](_, mutex)
        )
    } yield results

  private def createCancellingTask[F[_]](
      id: Int,
      mutex: Mutex[F]
  )(implicit A: Async[F]): F[Int] =
    if (id % 2 == 0)
      createLockingTask(
        id,
        mutex
      ) // even IDs will be the same tasks we tested earlier
    else
      for { // naughty for-comp
        fib <-
          createLockingTask(id, mutex)
            .onCancel(A.pure(s"[task $id] cancelling...").debug.void)
            .start
        _ <- A.sleep(2.seconds) >> fib.cancel
        out <- fib.join
        result <-
          out match {
            case Succeeded(fa) => fa
            case Errored(_)    => A.pure(-1)
            case Canceled()    => A.pure(-2)
          }
      } yield result

  private def demoCancellingTasks[F[_]: Async: Parallel]: F[List[Int]] =
    for {
      mutex <- Mutex.create[F]
      results <-
        (1 to 10).toList.parTraverse[F, Int](
          createCancellingTask[F](_, mutex)
        )
    } yield results

  override def run: IO[Unit] = demoCancellingTasks[IO].debug.void
}
