package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp}
import cats.implicits.catsSyntaxOptionId
import com.rockthejvm.utils._

import java.util.concurrent.Executors
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object AsyncIOs extends IOApp.Simple {
  /*
   * IOs can run asynchronously on fibers, without having to manually
   * manage the fiber lifecycle
   *
   * Uses callbacks, as usual with async computing
   */
  private val threadPool = Executors.newFixedThreadPool(10)
  private val ec = ExecutionContext.fromExecutorService(threadPool)

  private type Callback[A] = Either[Throwable, A] => Unit

  /*
   * example of a computation that should be delegated to a different
   * thread
   */
  private def computeANumber: Int = {
    Thread.sleep(1000)
    println(s"[${Thread.currentThread().getName}] computing a number")
    20
  }
  private def computeANumberTry: Either[Throwable, Int] =
    Try(computeANumber).toEither // converts Try[A] to Either[Throwable, A]

  private def computeANumberOnThreadPool(): Unit = {
    /*
     * Schedules computeANumber to run on threadPool
     *
     * The problem: this returns Unit, we don't have access to the value
     * We need to be able to lift this into an IO[Int]
     */
    threadPool.execute(() => computeANumber)
  }

  /*
   * lift an async computation to an IO using callbacks
   * Use async_ not async - latter is more complicated
   *
   * It is a 'foreign-function interface' - a way to make use of calls
   * not made in cats-effect
   */
  private val asyncNumberIO: IO[Int] = IO.async_ { cb =>
    /*
     * cats-effect blocks (semantically) until cb is invoked by
     * some other thread
     */
    threadPool.execute { () =>
      val result = computeANumberTry
      cb(result)
    }
  }

  /** Exercises */

  /* 1. Generalise async for A */
  private def asyncToIO[A](computation: () => A)(
      ec: ExecutionContext
  ): IO[A] =
    IO.async_ { cb =>
      ec.execute { () =>
        println(s"[${Thread.currentThread().getName}] running computation")
        val result = Try(computation()).toEither
        cb(result)
      }
    }

  private val asyncNumberIOv2 =
    asyncToIO(() => 20)(ec)

  /* 2. Lift a Future[A] into an IO[A] using async */
  private lazy val aNumberFuture = Future(computeANumber)(ec)

  private def futureToIO[A](fa: => Future[A])(ec: ExecutionContext): IO[A] =
    IO.async_ { cb =>
      // solution uses onComplete instead of andThen
      fa.andThen { res =>
        cb(res.toEither)
      }(ec)
    }
  private val futureNumberIO = futureToIO(aNumberFuture)(ec)

  // There is actually already an API for this
  private val futureNumberIOv2 =
    IO.fromFuture(
      IO(aNumberFuture) // but must be wrapped in an IO
    )

  /* 3. Define a never-ending IO */
  private val neverEndingIO =
    IO.async_ { _: Callback[Unit] =>
      threadPool.execute { () =>
        while (true) {}
      }
    }

  // solution: No need for all this - can just do this (no callback, no finish):
  private val betterNeverEndingIO = IO.async_[Int](_ => ())
  // and of course, can just use IO.never

  /** Full async call (IO.async)
    * Lets you call a finalizer in case your call is cancelled
    */
  private def demoAsyncCancellation = {
    val asyncNumberIOv2: IO[Int] =
      IO.async { cb: Callback[Int] =>
        /*
         * lambda must return IO[Option[IO[Unit]]]
         *
         * This is because:
         * 1. Finalizers are of type IO[Unit]
         * 2. We don't _need_ to specify a finalizer => Option[IO[Unit]]
         * 3. Creating an option is an effect => final IO
         */
        IO {
          threadPool.execute { () =>
            val result = computeANumberTry
            cb(result)
          }
        }.as(IO("Cancelled").debug.void.some)
      }

    for {
      fib <- asyncNumberIOv2.start
      _ <-
        IO.sleep(500.millis) >>
          IO("cancelling...").debug >>
          fib.cancel
      _ <- fib.join
    } yield ()
  }

  override def run: IO[Unit] =
    demoAsyncCancellation.debug >>
      IO(threadPool.shutdown())
}
