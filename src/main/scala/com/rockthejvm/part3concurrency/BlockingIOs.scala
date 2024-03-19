package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp}
import com.rockthejvm.utils._

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object BlockingIOs extends IOApp.Simple {
  private val someSleeps =
    for {
      /*
       * These sleeps will happen on different threads
       *
       * These threads are NOT BLOCKED - they just use 'semantic blocking'
       * (now known as fiber blocking)
       *
       * Effectively, IO.sleep involves suspending the fiber it is
       * running on and yielding control of the thread it is running on.
       * (This is why the second sleep will be on a different thread.)
       *
       * Fiber blocking is implemented using the Deferred trait, a purely
       * functional promise that can only be completed once
       */
      _ <- IO.sleep(1.second).debug
      _ <- IO.sleep(1.second).debug
    } yield ()

  /*
   * REALLY blocking IOs - use IO.blocking
   *
   * IO.blocking will execute on a separate thread pool (io-blocking)
   * (IOs normally execute on io-compute)
   */
  private val aBlockingIO =
    IO.blocking {
      Thread.sleep(1000)
      println(
        s"[${Thread.currentThread().getName}] finished blocking computation"
      )
    }

  /*
   * You can control how you yield control of a thread:
   *  IO.cede
   *
   * By ceding several times, these IOs can be run on 3 different threads.
   * Otherwise, all IOs WILL run on the same thread sequentially.
   */
  private val iosOnManyThreads =
    for {
      _ <- IO("first").debug
      // yield control, rest of for-comp can be on a different thread
      _ <- IO.cede
      _ <- IO("second").debug
      _ <- IO.cede
      _ <- IO("third").debug
    } yield ()

  /*
   * 2 computations, separated by a cede, 1000 times.
   *
   * If using IORuntime, it will probably be executed on the same
   * thread, as cats-effect uses batching to try tries and minimise
   * thread-switching where possible.
   *
   * But we will be able to see thread-switching if we use our own
   * ExecutionContext, as batching will be less efficient.
   */
  private def testThousandEffectsSwitch: IO[Int] = {
    val ec: ExecutionContext =
      ExecutionContext.fromExecutorService(
        Executors.newFixedThreadPool(8)
      )
    (1 to 1000)
      .map(IO.pure)
      .reduce(_.debug >> IO.cede >> _.debug)
      .evalOn(ec)
  }

  override def run: IO[Unit] = testThousandEffectsSwitch.void
}
