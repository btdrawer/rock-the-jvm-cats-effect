package com.rockthejvm.part4coordination

import cats.effect.implicits.genSpawnOps
import cats.effect.std.Semaphore
import cats.effect.{Async, IO, IOApp}
import cats.implicits._
import com.rockthejvm.utils.DebugWrapper

import scala.concurrent.duration.DurationInt
import scala.util.Random

object Semaphores extends IOApp.Simple {

  /** Semaphores:
    * A synchronization primitive that will allow only a certain number
    * of threads into a critical region.
    *
    * Has an internal counter called 'permits' that are allocated to
    * different threads.
    * If a thread requests more permits than are available, that thread
    * will block.
    *
    * Built on top of Ref and Deferred.
    * (A Mutex is basically a semaphore with 1 permit.)
    */
  private val semaphore: IO[Semaphore[IO]] =
    Semaphore[IO](2) // 2 permits

  // example: limiting the number of concurrent sessions on a server
  private def doWorkWhileLoggedIn[F[_]](implicit A: Async[F]): F[Int] =
    A.sleep(1.second) >> A.delay(Random.nextInt(100))

  private def login[F[_]](id: Int, semaphore: Semaphore[F])(implicit
      A: Async[F]
  ): F[Int] =
    for {
      _ <- A.delay(s"[session $id] waiting to log in...").debug
      _ <- semaphore.acquire
      // critical section
      _ <- A.delay(s"[session $id] logged in, working...").debug
      result <- doWorkWhileLoggedIn[F]
      _ <- A.delay(s"[session $id] done: $result, logging out...").debug
      // end of critical section
      _ <- semaphore.release
    } yield result

  private def demoSemaphore[F[_]: Async]: F[Unit] =
    for {
      semaphore <- Semaphore[F](2)
      // 3 users but only 2 permits -> 1 user will have to wait
      user1Fib <- login(1, semaphore).start
      user2Fib <- login(2, semaphore).start
      user3Fib <- login(3, semaphore).start
      _ <- user1Fib.join
      _ <- user2Fib.join
      _ <- user3Fib.join
    } yield ()

  // you can request multiple permits using `acquireN` and `releaseN`
  private def weightedLogin[F[_]](
      id: Int,
      requiredPermits: Int,
      semaphore: Semaphore[F]
  )(implicit A: Async[F]): F[Int] =
    for {
      _ <- A.delay(s"[session $id] waiting to log in...").debug
      _ <- semaphore.acquireN(requiredPermits)
      // critical section
      _ <- A.delay(s"[session $id] logged in, working...").debug
      result <- doWorkWhileLoggedIn[F]
      _ <- A.delay(s"[session $id] done: $result, logging out...").debug
      // end of critical section
      _ <- semaphore.releaseN(requiredPermits)
    } yield result

  private def demoWeightedSemaphore[F[_]: Async]: F[Unit] =
    for {
      semaphore <- Semaphore[F](2)
      user1Fib <- weightedLogin(1, 1, semaphore).start
      user2Fib <- weightedLogin(2, 2, semaphore).start
      // will never work because requires more permits than semaphore has
      user3Fib <- weightedLogin(3, 3, semaphore).start
      _ <- user1Fib.join
      _ <- user2Fib.join
      _ <- user3Fib.join
    } yield ()

  /** Exercise (remember semaphore with 1 permit == mutex)
    * What is wrong with those code and how do we fix it?
    */
  private val mutex = Semaphore[IO](1)
  private val users =
    (1 to 10).toList.parTraverse { id =>
      for {
        semaphore <- mutex
        result <- login[IO](id, semaphore)
      } yield result
    }

  /*
   * The problem is that we are initialising a separate mutex
   * for each user. We need to have the same mutex across all users,
   * as otherwise they will run in parallel.
   */
  private val correctUsers =
    for {
      semaphore <- mutex
      result <-
        (1 to 10).toList
          .parTraverse(login[IO](_, semaphore))
    } yield result

  override def run: IO[Unit] = correctUsers.debug.void
}
