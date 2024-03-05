package com.rockthejvm.part2effects

import scala.io.StdIn

object Effects extends App {

  /** Substitution: I can replace an expression with the value it evaluates to
    * (aka referential transparency).
    */

  private def combine(a: Int, b: Int): Int = a + b
  // Pure FP - these are all the same:
  private val five = combine(2, 3)
  private val fiveV2 = 2 + 3
  private val fiveV3 = 5

  // Impure FP - these are not the same:
  private val printSomething: Unit = println("printing something")
  private val printSomethingV2: Unit = ()

  /**  Side effects are necessary for useful programs - basically no programs are pure
    *  But we want to use FP to reason about our program more effectively
    *
    *  For this, we use EFFECTS: An effect is a data type that _embodies_ a side effect
    *
    *  Properties of effect types:
    *  1. Type signature describes the kind of computation that is performed
    *  2. What kind of value will be produced by that computation
    *  3. _When side effects are needed_, the construction of the effect should be separate from the effect execution
    *
    * Example of an effect type: Option
    * It meets all 3 properties:
    * 1. Describes a possibly absent value
    * 2. We know that, e.g., an Option[Int] wraps an Int
    * 3. Option[Int] doesn't produce any side effects
    *
    * NOT an effect type: Future
    * Does it meet the properties?
    * 1. Yes - Describes an asynchronous computation
    * 2. Yes - e.g., a Future[Int] wraps an Int
    * 3. NO - Futures execute side effects on construction
    *
    * Final example: MyIO (defined below)
    * Does it meet the properties?
    * 1. Yes - Describes any computation that might produce side effects
    * 2. Yes - the return type is in the type signature
    * 3. Yes - it does not execute side effects when constructed
    * -> Yes
    *
    * IO is the holy grail of bridging pure FP and impure computation,
    * because it is the most _generic_ effect imaginable.
    * It forms the basis of cats-effect.
    */
  final case class MyIO[A](unsafeRun: () => A) {
    def map[B](f: A => B): MyIO[B] =
      MyIO(() => f(unsafeRun()))

    def flatMap[B](f: A => MyIO[B]): MyIO[B] =
      MyIO(() => f(unsafeRun()).unsafeRun())
  }

  /** Exercises */

  /* 1. A MyIO which returns the current time of the system. */
  // NB can be a val
  private def currentTime: MyIO[Long] =
    MyIO(() => System.currentTimeMillis())

  /* 2. A MyIO which measures the duration of a computation. */
  private def measure[A](fa: MyIO[A]): MyIO[Long] =
    for {
      startTime <- currentTime
      _ <- fa
      endTime <- currentTime
    } yield endTime - startTime

  /* 3. A MyIO which prints something to the console. */
  private def print(str: String): MyIO[Unit] =
    MyIO(() => println(str))

  /* 4. A MyIO which reads a line from the std input. */
  // NB can also be a val
  private def readStdInLine: MyIO[String] =
    MyIO(() => StdIn.readLine())

  private val measuring: MyIO[Unit] =
    for {
      timeTaken <-
        measure(
          MyIO(() => Thread.sleep(500))
        )
      _ <- print(timeTaken.toString)
    } yield ()

  private val concatenate: MyIO[Unit] =
    for {
      in1 <- readStdInLine
      in2 <- readStdInLine
      _ <- print(in1 + in2)
    } yield ()

  measuring.unsafeRun()
  concatenate.unsafeRun()
}
