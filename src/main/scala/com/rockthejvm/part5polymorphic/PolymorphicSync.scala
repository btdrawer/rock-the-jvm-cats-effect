package com.rockthejvm.part5polymorphic

import cats.Defer
import cats.effect.kernel.MonadCancel
import cats.effect.{IO, IOApp, Sync}
import cats.implicits._

import java.io.{BufferedReader, InputStreamReader}
import scala.io.StdIn

object PolymorphicSync extends IOApp.Simple {
  // delay: The ability to suspend computations in an effect
  private val aDelayedIO =
    IO.delay {
      println("I'm an effect")
      ()
    }

  // blocking: performed on a specific thread pool for blocking computations
  private val aBlockingIO =
    IO.blocking {
      println("Loading...")
      Thread.sleep(1000)
      ()
    }

  /*
   * Synchronous computation: The ability to
   * 1. run some external computation wrapped inside cats effect
   * 2. run blocking computations
   *
   * Described in trait Sync[F], which extends MonadCancel with
   * Throwable as the error type
   */
  trait MySync[F[_]] extends MonadCancel[F, Throwable] with Defer[F] {
    def delay[A](thunk: => A): F[A] // suspension of computation in F
    def blocking[A](thunk: => A): F[A] // runs on blocking thread pool
    def defer[A](thunk: => F[A]): F[A] = flatten(
      delay(thunk)
    ) // defer comes for free and is part of Defer[F]
  }

  private val syncIO = Sync[IO]

  /*
   * Abilities:
   * - pure (Applicative)
   * - map/flatMap (Monad)
   * - raiseError (MonadError)
   * - uncancelable (MonadCancel)
   * - delay/blocking (Sync)
   */
  private val aDelayedIOv2 =
    syncIO.delay {
      println("I'm an effect")
      ()
    }
  private val aBlockingIOv2 =
    syncIO.blocking {
      println("Loading...")
      Thread.sleep(1000)
      ()
    }
  // defer - delay an effect
  private val aDeferredIO = IO.defer(aDelayedIO)

  /** Exercise - Write a polymorphic console */
  trait Console[F[_]] {
    def println[A](a: A): F[Unit]
    def readLine(): F[String]
  }

  private object Console {
    def apply[F[_]](implicit S: Sync[F]): F[Console[F]] =
      new Console[F] {
        override def println[A](a: A): F[Unit] =
          S.delay(Predef.println(a))

        override def readLine(): F[String] =
          S.delay(StdIn.readLine())
      }.pure[F]

    /*
     * The above basically works, however methods should be blocking
     * so that we aren't hogging the main cats-effect thread pool
     *
     * This is the solution from the video
     */
    def v2[F[_]](implicit S: Sync[F]): F[Console[F]] =
      (System.in -> System.out).pure[F].map { case (in, out) =>
        new Console[F] {
          override def println[A](a: A): F[Unit] =
            S.blocking(out.println(a))

          override def readLine(): F[String] = {
            val bufferedReader = new BufferedReader(new InputStreamReader(in))
            /*
             * You can also use S.interruptible if you want it to be possible
             * for the thread to be interrupted - this is to avoid having the
             * blocking computation forever block one of your threads.
             */
            S.blocking(bufferedReader.readLine())
          }
        }
      }
  }

  /*
   * NB Once you get to Sync, you're basically talking about IO
   *
   * There are very few other monads with these properties, and
   * they're basically all transformable to IO anyway
   *
   * So is it worth generalising such code?
   *
   * (IMO there is no cost to generalising so may as well)
   */

  override def run: IO[Unit] =
    for {
      console <- Console.v2[IO]
      line <- console.readLine()
      _ <- console.println(line)
    } yield ()
}
