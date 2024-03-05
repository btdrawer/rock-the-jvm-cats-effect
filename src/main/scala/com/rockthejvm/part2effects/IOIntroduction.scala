package com.rockthejvm.part2effects

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import scala.annotation.tailrec
import scala.io.StdIn

object IOIntroduction extends App {
  // Embodies any computation that might include side effects.

  /*
   * `pure` expressions are evaluated eagerly.
   * It therefore describes a pure computation.
   * It SHOULD NOT CONTAIN SIDE EFFECTS.
   */
  private val ourFirstIO: IO[Int] = IO.pure(42)
  /*
   * `delay` expressions are evaluated when executed.
   * Use `delay` when a side effect is involved.
   * If in doubt about whether there are side effects, use `delay`.
   */
  private val aDelayedIO: IO[Int] =
    IO.delay({
      println("I am creating a side effect while producing an integer")
      72
    })
  /* The `apply` method === `delay` - assumes side effects may happen. */
  private val aDelayedIOV2: IO[Int] =
    IO({
      println("I am creating a side effect while producing an integer")
      72
    })

  private val smallProgram: IO[Unit] =
    for {
      in1 <- IO(StdIn.readLine())
      in2 <- IO(StdIn.readLine())
      _ <- IO.delay(println(in1 + in2))
    } yield ()

  /*
   * The 'end of the world' - the only place where effects will be executed.
   *
   * Use unsafeRunSync() to run the IO.
   * Requires an IORuntime.
   */
//  smallProgram.unsafeRunSync()

  /** Transformations */

  /* mapN - combine IO effects as tuples */
  import cats.syntax.apply._

  private val doubleFirstIO =
    ourFirstIO.map(_ * 2)
  private val combinedIO: IO[Int] =
    (ourFirstIO, doubleFirstIO).mapN(_ + _)

  // More concise version of smallProgram using mapN
  private val smallProgramV2: IO[Unit] =
    (IO(StdIn.readLine()), IO(StdIn.readLine()))
      .mapN(_ + _)
      .map(println)

//  smallProgramV2.unsafeRunSync()

  /** Exercises */

  /* 1. Sequence 2 IOs and take the result of the last one */
  private def sequenceTakeLast[A, B](ioa: IO[A], iob: IO[B]): IO[B] =
    for {
      _ <- ioa
      b <- iob
    } yield b

  // NB can also use *> - "andThen"
  private def sequenceTakeLastV2[A, B](ioa: IO[A], iob: IO[B]): IO[B] =
    ioa *> iob

  // And also >> - "andThen" with name call (ie lazily-evaluated)
  private def sequenceTakeLastV3[A, B](ioa: IO[A], iob: IO[B]): IO[B] =
    ioa >> iob

  /* 2. Same but return the first one */
  private def sequenceTakeFirst[A, B](ioa: IO[A], iob: IO[B]): IO[A] =
    for {
      a <- ioa
      _ <- iob
    } yield a

  // NB there is also a <*
  private def sequenceTakeFirstV2[A, B](ioa: IO[A], iob: IO[B]): IO[A] =
    ioa <* iob

  /* 3. Repeat an IO forever */
  private def forever[A](ioa: IO[A]): IO[A] =
    for {
      _ <- ioa
      res <- forever(ioa)
    } yield res

  // NB this is the same thing, since we don't care about the result of ioa
  private def foreverV2[A](ioa: IO[A]): IO[A] =
    ioa >> foreverV2(ioa)
  // BUT you can't use the eager *> - it just keeps evaluating eagerly -> StackOverflowError
  // This doesn't happen with lazy because all it does is create a new data structure (FlatMap)
  // which is executed later
  // foreverV2 is basically an infinite linked list, chained together tailrec-style behind the scenes

  // NB shortcut built into cats
  private def foreverV3[A](ioa: IO[A]): IO[A] =
    ioa.foreverM

//  forever(
//    IO(println("hi"))
//  ).unsafeRunSync()

  /* 4. Convert an IO to a different type. */
  private def convert[A, B](ioa: IO[A], b: B): IO[B] =
    ioa.map(_ => b)

  // NB there is also a shortcut here - `.as`
  private def convertV2[A, B](ioa: IO[A], b: B): IO[B] =
    ioa.as(b)

  /* 5. Discard value inside IO and return Unit */
  private def asUnit[A](ioa: IO[A]): IO[Unit] =
    ioa.map(_ => ())

  // NB - not recommended
  private def asUnitV2[A](ioa: IO[A]): IO[Unit] =
    ioa.as(())

  // NB better - use `void`
  private def asUnitV3[A](ioa: IO[A]): IO[Unit] =
    ioa.void

  /* 6. Fix stack recursion */
  private def sum(n: Int): IO[Int] = {
    @tailrec
    def sumInner(n: Int, acc: Int): IO[Int] =
      if (n <= 0) {
        IO.pure(acc)
      } else {
        sumInner(n - 1, acc + n)
      }
    sumInner(n, 0)
  }

  // I did this version after looking up how to do recursion in cats-effect
  // IO supports stack-safe monad recursion, so this should be stack-safe
  private def sumV2(n: Int): IO[Int] = {
    def sumInner(ion: IO[Int], ioacc: IO[Int]): IO[Int] =
      for {
        n <- ion
        result <-
          if (n <= 0) {
            ioacc
          } else {
            for {
              acc <- ioacc
              res <- sumInner(IO.pure(n - 1), IO.pure(acc + n))
            } yield res
          }
      } yield result
    sumInner(IO.pure(n), IO.pure(0))
  }

  // NB my solutions above work, but Daniel's answer is cleaner - and still stack-safe
  private def sumAnswer(n: Int): IO[Int] =
    if (n <= 0) IO(0)
    else
      for {
        lastNumber <- IO(n)
        prevSum <- sumAnswer(n - 1)
      } yield prevSum + lastNumber

  // All 3 produce the same result
  (for {
    summed <- sum(120)
    _ <- IO(println(summed))

    summedV2 <- sumV2(120)
    _ <- IO(println(summedV2))

    summedAnswer <- sumAnswer(120)
    _ <- IO(println(summedAnswer))
  } yield ()).unsafeRunSync()

  /* 7. Fibonacci IO that does not crash on recursion */
  private def fibonacci(n: Int): IO[BigInt] = {
    def fibonacciInner(n: Int, a: BigInt, b: BigInt): IO[BigInt] =
      if (n <= 0) {
        IO.pure(a + b)
      } else {
        fibonacciInner(n - 1, b, b + n)
      }
    fibonacciInner(n, 0, 1)
  }

  // Again, after looking up how to do recursion,
  // try using flatMap more
  private def fibonacciV2(n: Int): IO[BigInt] = {
    def fibonacciInner(
        ion: IO[Int],
        ioa: IO[BigInt],
        iob: IO[BigInt]
    ): IO[BigInt] =
      for {
        n <- ion
        a <- ioa
        b <- iob
        result <-
          if (n <= 0) {
            IO.pure(a + b)
          } else {
            fibonacciInner(
              IO.pure(n - 1),
              IO.pure(b),
              IO.pure(n + b)
            )
          }
      } yield result
    fibonacciInner(
      IO.pure(n),
      IO.pure(0),
      IO.pure(1)
    )
  }

  // My solutions were WRONG - this works:
  private def fibonacciAnswer(n: Int): IO[BigInt] =
    if (n < 2) IO(1)
    else
      for {
        // Need to defer because we initially get IO[IO[BigInt]]
        //  IO.defer === IO.delay(...).flatten
        // By wrapping recursive calls in IOs we get STACK SAFETY
        last <- IO.defer(fibonacciAnswer(n - 1))
        prev <- IO.defer(fibonacciAnswer(n - 2))
      } yield last + prev

  (for {
    fib <- fibonacci(32)
    _ <- IO(println(fib))

    fibV2 <- fibonacciV2(32)
    _ <- IO(println(fibV2))

    fibAnswer <- fibonacciAnswer(32)
    _ <- IO(println(fibAnswer))
  } yield ()).unsafeRunSync()
}
