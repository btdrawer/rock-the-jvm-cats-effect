package com.rockthejvm.part2effects

import cats.effect.IO
import cats.effect.unsafe.implicits.global

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
  smallProgram.unsafeRunSync()

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

  smallProgramV2.unsafeRunSync()
}
