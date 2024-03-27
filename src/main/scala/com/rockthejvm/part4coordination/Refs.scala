package com.rockthejvm.part4coordination

import cats.effect.{IO, IOApp, Ref}
import cats.implicits.catsSyntaxTuple2Parallel
import com.rockthejvm.utils.DebugWrapper

import scala.concurrent.duration.DurationInt

object Refs extends IOApp.Simple {

  /** Ref: A purely functional atomic reference
    * (always thread-safe)
    */
  private val atomicNumber: IO[Ref[IO, Int]] =
    Ref[IO].of(20)
  // IO.ref is available as shorthand for IO
  private val atomicNumberV2: IO[Ref[IO, Int]] =
    IO.ref(20)

  // ALL of these operations are thread-safe

  // modifying a value is an effect
  private val increasedNumber: IO[Unit] =
    atomicNumber.flatMap(_.set(21))

  // obtaining a value
  private val aNumber: IO[Int] =
    atomicNumber.flatMap(_.get)

  // can also get and set in one transaction
  private val getAndSetNumber: IO[Int] =
    atomicNumber.flatMap(_.getAndSet(21)) // returns the OLD value

  // update
  private val fNumber: IO[Unit] =
    atomicNumber.flatMap(_.update(_ + 2))

  // can also get the old or new value when updating
  private val updatedNumber: IO[Int] =
    atomicNumber.flatMap(_.updateAndGet(_ + 2)) // new value
  private val oldNumber: IO[Int] =
    atomicNumber.flatMap(_.getAndUpdate(_ + 2)) // old value

  /*
   * modifying with a function but returning a different type
   * updates the value and returns something else
   */
  private val modifiedNumber =
    atomicNumber.flatMap(_.modify { value =>
      (value * 10) -> s"my current value is $value"
    })

  /*
   * Why?
   *
   * Main use case is concurrent/thread-safe reads/writes
   * over shared values (using a purely functional interface)
   *
   * e.g.: Distributed work, such as a word count
   */

  // This is an impure approach
  private def demoConcurrentWorkImpure(): IO[Unit] = {
    import cats.syntax.parallel._
    var count = 0

    def task(workload: String): IO[Unit] = {
      val wordCount = workload.split(" ").length
      for {
        _ <- IO(s"Counting words for '$workload': $wordCount").debug
        newCount <- IO(count + wordCount)
        _ <- IO(s"New total: $newCount").debug
        _ <- IO(count += wordCount)
      } yield ()
    }

    /*
     * This is the problem:
     * We want to run these operations in parallel, but the var
     * is not thread-safe, and so the word count we end up with is
     * not accurate
     */
    List("Hello world", "This ref thing is useless", "Yeah ok cool")
      .map(task)
      .parSequence
      .void
  }

  // Pure approach (I treated this as an exercise)
  private def demoConcurrentWorkPure(): IO[Unit] = {
    import cats.syntax.parallel._

    def task(count: Ref[IO, Int], workload: String): IO[Unit] = {
      val wordCount = workload.split(" ").length
      for {
        _ <- IO(s"Counting words for '$workload': $wordCount").debug
        newCount <- count.updateAndGet(_ + wordCount)
        _ <- IO(s"New total: $newCount").debug
      } yield ()
    }

    for {
      count <- IO.ref(0)
      _ <-
        List("Hello world", "This ref thing is useless", "Yeah ok cool")
          .map(task(count, _))
          .parSequence
          .void // (redundant)
    } yield ()
  }

  /*
   * ^ Same as solution given in video, but seems wrong?
   * I do not see correct increments
   */

  /** Exercises */

  /* 1. Refactor an impure function */
  private def tickingClockImpure(): IO[Unit] = {
    var ticks: Long = 0L

    def tickingClock: IO[Unit] =
      for {
        _ <- IO.sleep(1.second)
        _ <- IO(System.currentTimeMillis()).debug
        _ <- IO(ticks += 1)
        _ <- tickingClock
      } yield ()

    def printTicks: IO[Unit] =
      for {
        _ <- IO.sleep(5.seconds)
        _ <- IO(s"TICKS: $ticks").debug
        _ <- printTicks
      } yield ()

    (tickingClock, printTicks).parTupled.void
  }

  private def tickingClockPure(): IO[Unit] = {
    def tickingClock(ticks: Ref[IO, Int]): IO[Unit] =
      for {
        _ <- IO.sleep(1.second)
        _ <- IO(System.currentTimeMillis()).debug
        _ <- ticks.update(_ + 1)
        _ <- tickingClock(ticks)
      } yield ()

    def printTicks(ticks: Ref[IO, Int]): IO[Unit] =
      for {
        _ <- IO.sleep(5.seconds)
        // STUPID - I forgot to call `ticks.get` and flatMap!
        t <- ticks.get
        _ <- IO(s"TICKS: $t").debug
        _ <- printTicks(ticks)
      } yield ()

    for {
      ticks <- IO.ref(0)
      _ <-
        (
          tickingClock(ticks),
          printTicks(ticks)
        ).parTupled
    } yield ()
  }

  /*
   * NB weird behaviour: If you initialise Ref outside the for-comp
   * and then flatMap on it, each flatMap is effectively a different
   * initialisation
   */
  private def tickingClockWeird(): IO[Unit] = {
    val ticks = IO.ref(0)

    def tickingClock: IO[Unit] =
      for {
        // each call starts a new ref
        t <- ticks
        _ <- IO.sleep(1.second)
        _ <- IO(System.currentTimeMillis()).debug
        _ <- t.update(_ + 1)
        _ <- tickingClock
      } yield ()

    def printTicks: IO[Unit] =
      for {
        // same here - so currentTicks is always 0
        t <- ticks
        _ <- IO.sleep(5.seconds)
        currentTicks <- t.get
        _ <- IO(s"TICKS: $currentTicks").debug
        _ <- printTicks
      } yield ()

    (tickingClock, printTicks).parTupled.void
  }

  override def run: IO[Unit] = tickingClockPure().debug
}
