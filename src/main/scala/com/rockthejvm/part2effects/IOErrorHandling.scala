package com.rockthejvm.part2effects

import cats.effect.{Async, IO, Resource}

import scala.util.{Failure, Success, Try}

object IOErrorHandling extends App {

  /** Failed computations suspended in IO */

  // We CAN do this...
  private val aFailedCompute: IO[Int] =
    IO.delay(
      throw new RuntimeException("a failure")
    )

  // ...or we can raiseError, which is more readable
  private val aFailure: IO[Int] =
    IO.raiseError(
      new RuntimeException("a proper fail")
    )

  // handle exceptions
  private val dealWithIt =
    aFailure.handleErrorWith { case _: RuntimeException =>
      IO.delay(println("I'm still here"))
    }

  // you can also turn potential errors into an Either using `attempt`
  private val effectAsEither: IO[Either[Throwable, Int]] =
    aFailure.attempt

  // transform failures and successes at once with redeem
  // e.g. here we transform both cases to String
  private val resultAsString: IO[String] =
    aFailure.redeem(
      th => s"FAIL: $th",
      value => s"SUCCESS: $value"
    )

  // redeemWith is like flatMap for redeem - returns a wrapped value
  private val resultAsStringEffect =
    aFailure.redeemWith(
      th => IO(s"FAIL: $th"),
      value => IO(s"SUCCESS: $value")
    )

  /** Exercises */

  /* 1. Construct potentially failed IOs from standard data types. */

  // Option
  def optionToIO[A](oa: Option[A])(ifEmpty: Throwable): IO[A] =
    oa match {
      case Some(a) => IO.pure(a)
      case None    => IO.raiseError[A](ifEmpty)
    }

  // Try
  def tryToIO[A](ta: Try[A]): IO[A] =
    ta match {
      case Success(a)  => IO.pure(a)
      case Failure(th) => IO.raiseError[A](th)
    }

  // Either
  def eitherToIO[A](ea: Either[Throwable, A]): IO[A] =
    ea match {
      case Left(th) => IO.raiseError(th)
      case Right(a) => IO.pure(a)
    }

  /* 2. Create 2 new methods */

  // handleError
  def handleIOError[A](ioa: IO[A])(handler: Throwable => A): IO[A] =
    ioa.redeem(
      handler,
      a => a // can use `identity` here
    )

  // handleErrorWith
  def handleIOErrorWith[A](ioa: IO[A])(handler: Throwable => IO[A]): IO[A] =
    ioa.redeemWith(
      handler,
      IO.pure
    )
}
