package com.rockthejvm.part5polymorphic

import cats.effect.{Async, Concurrent, IO, IOApp, Sync, Temporal}
import cats.implicits._

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import com.rockthejvm.utils.general._

object PolymorphicAsync extends IOApp.Simple {
  /*
   * Async is the most powerful type class in cats-effect.
   *
   * It is responsible for suspending asynchronous computations
   * that are executed elsewhere (suspended in F).
   *
   * It extends basically everything via Sync and Temporal
   */
  trait MyAsync[F[_]] extends Sync[F] with Temporal[F] {
    def executionContext: F[ExecutionContext]
    def async[A](
        cb: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]
    ): F[A]
    def async_[A](cb: (Either[Throwable, A] => Unit) => Unit): F[A]
    def evalOn[A](fa: F[A], ec: ExecutionContext): F[A]
    def never[A]: F[A] // never-ending effect
  }

  private val asyncIO = Async[IO]

  /** New abilities */

  /* 1. Expose an ExecutionContext */
  private val ec: IO[ExecutionContext] = asyncIO.executionContext

  /* 2. async_ & async functions (foreign-function interface/FFI) */
  private val threadPool = Executors.newFixedThreadPool(10)

  private type Callback[A] = Either[Throwable, A] => Unit

  private val asyncNumber: IO[Int] =
    asyncIO.async_ { (cb: Callback[Int]) =>
      // start computation on some other thread pool
      threadPool.execute { () =>
        println(s"[${Thread.currentThread().getName}] Computing a number")
        cb(Right(20))
      }
    }

  private val complexAsyncNumber: IO[Int] =
    asyncIO.async { (cb: Callback[Int]) =>
      IO {
        threadPool.execute { () =>
          println(s"[${Thread.currentThread().getName}] Computing a number")
          cb(Right(20))
        }
      }.as(
        IO("cancelled!").debug.void.some
      )
    }

  // different thread pool

  private val myExecutionContext =
    ExecutionContext.fromExecutorService(threadPool)
  private val asyncNumber_v3 =
    asyncIO
      .evalOn(IO(20).debug, myExecutionContext)
      .guarantee(IO(threadPool.shutdown()))

  // never

  private val neverIO = asyncIO.never

  /** Exercises */

  /* 1. Implement `never` and `async_` in terms of the big `async` */

  private def never[F[_]: Async, A]: F[A] =
    Async[F].async { (_: Callback[A]) =>
      threadPool.execute { () =>
        while (true) {}
      }
      ().pure[F].some.pure[F]
    }

  // video solutions:
  private def neverSolution[F[_]: Async, A]: F[A] =
    Async[F].async_(_ => ()) // callback never invoked

  private def async_[F[_]: Async, A](
      cb: (Either[Throwable, A] => Unit) => Unit
  ): F[A] =
    Async[F].async { kb =>
      cb(kb)
        .pure[F]
        .map(_ => None) // None because there is no cancellation listener here
    }

  /* 2. Tuple 2 effects with different requirements */

  private def firstEffect[F[_]: Concurrent, A](a: A): F[A] =
    a.pure[F]
  private def secondEffect[F[_]: Sync, A](a: A): F[A] =
    a.pure[F]

  /*
   * couldn't figure this one out, but turns out it was quite simple
   * Just require Async as it satisfies all other requirements
   */
  private def tupledEffect[F[_]: Async, A](a: A): F[(A, A)] =
    for {
      first <- firstEffect(a)
      second <- secondEffect(a)
    } yield (first, second)

  override def run: IO[Unit] = never[IO, Unit]
}
