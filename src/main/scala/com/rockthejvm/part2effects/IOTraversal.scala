package com.rockthejvm.part2effects

import cats.effect.{IO, IOApp}
import cats.implicits.toTraverseOps
import com.rockthejvm.utils.DebugWrapper

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

object IOTraversal extends IOApp.Simple {

  /** Future */

  // Counts words and simulates a heavy computation by sleeping for a second
  private def heavyComputation(str: String): Future[Int] = Future {
    Thread.sleep(Random.nextInt(1000))
    str.split(" ").length
  }

  private val workload = List(
    "I like cats effect",
    "a b c d",
    "hello world"
  )
  // This is a List[Future[Int]] - want to get to Future[List[Int]]
  private val futures: List[Future[Int]] = workload.map(heavyComputation)
  private def clunkyFutures(): Unit = {
    futures.foreach(_.foreach(println))
  }

  // This is how you do it
  import cats.Traverse
  import cats.instances.list._
  private val listTraverse = Traverse[List]
  private val singleFuture = listTraverse.traverse(workload)(heavyComputation)
  private def traverseFutures(): Unit = {
    singleFuture.foreach(println)
  }

  /** IO */

  // same as heavy computation but suspended in IO
  private def computeAsIO(str: String): IO[Int] = IO {
    Thread.sleep(Random.nextInt(1000))
    str.split(" ").length
  }.debug

  // Same as clunkyFutures
  private val ios: List[IO[Int]] = workload.map(computeAsIO)
  private val singleIO: IO[List[Int]] =
    listTraverse.traverse(workload)(computeAsIO)

  /** Parallelism - parTraverse */
  import cats.syntax.parallel._
  private val parallelSingleIO: IO[List[Int]] =
    workload.parTraverse(computeAsIO)

  /** Exercises */
  private def sequence[A](ios: List[IO[A]]): IO[List[A]] =
    sequenceF(ios)

  private def sequenceF[F[_]: Traverse, A](ios: F[IO[A]]): IO[F[A]] =
    ios.traverse(identity)

  private def parSequence[A](ios: List[IO[A]]): IO[List[A]] =
    parSequenceF(ios)

  private def parSequenceF[F[_]: Traverse, A](ios: F[IO[A]]): IO[F[A]] =
    ios.parTraverse(identity)

  override def run: IO[Unit] =
    parallelSingleIO.map(_.sum).debug.void
}
