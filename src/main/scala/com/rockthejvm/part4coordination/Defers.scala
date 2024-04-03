package com.rockthejvm.part4coordination

import cats.effect.implicits._
import cats.effect.kernel.{Async, Outcome}
import cats.effect.{Deferred, Fiber, IO, IOApp, Ref}
import cats.implicits._
import com.rockthejvm.utils.DebugWrapper

import scala.concurrent.duration.DurationInt

object Defers extends IOApp.Simple {

  /** Deferred: A concurrency primitive for waiting for an effect
    * while some other effect completes with a value
    *
    * Essentially, pure-FP promises
    */

  // these are the same thing
  private val aDeferred: IO[Deferred[IO, Int]] =
    Deferred[IO, Int]
  private val aDeferredV2 = IO.deferred[Int]

  /*
   * `get` (IO[A]) will semantically block the fiber calling it
   * until the Deferred has a value
   */
  private val reader: IO[Int] =
    aDeferred.flatMap(_.get)

  /*
   * `complete` to set the value of Deferred
   *
   * Returns true if the deferred had not been completed
   * until now; returns false if it was already completed.
   */
  private val writer: IO[Boolean] =
    aDeferred.flatMap(_.complete(20))

  private def demoDeferred(): IO[Unit] = {
    def consumer(signal: Deferred[IO, Int]): IO[Int] =
      for {
        _ <- IO("[consumer] waiting for result").debug
        aNumber <- signal.get
        _ <- IO(s"[consumer] got the result: $aNumber").debug
      } yield aNumber

    def producer(signal: Deferred[IO, Int]): IO[Unit] =
      for {
        _ <- IO("[producer] setting result").debug
        _ <- IO.sleep(1.second)
        _ <- IO("[producer] complete: 20").debug
        aNumber <- IO(20)
        _ <- signal.complete(aNumber)
      } yield ()

    for {
      signal <- IO.deferred[Int]
      fibConsumer <- consumer(signal).start
      fibProducer <- producer(signal).start
      _ <- fibConsumer.join
      _ <- fibProducer.join
    } yield ()
  }

  // simulate downloading some content
  private val fileParts =
    List("I ", "love S", "ala", " with Cat", "s Effect<EOF>")

  /*
   * Using refs for this use case (downloading a file) involves
   * 'busy waiting': spinning and checking for something to
   * complete.
   */
  private def fileNotifierWithRef(): IO[Unit] = {
    def downloadFile(contentRef: Ref[IO, String]): IO[Unit] =
      fileParts
        .map { part =>
          IO(s"[downloader] got '$part'").debug >>
            IO.sleep(1.second) >>
            contentRef.update(_ + part)
        }
        .sequence
        .void

    def notifyFileComplete(contentRef: Ref[IO, String]): IO[Unit] =
      for {
        file <- contentRef.get
        _ <-
          if (file.endsWith("<EOF>")) {
            IO("[notifier] file download complete").debug
          } else {
            IO("[notifier] downloading...").debug >>
              IO.sleep(500.millis) >>
              notifyFileComplete(contentRef)
          }
      } yield ()

    for {
      contentRef <- Ref[IO].of("")

      downloader <- downloadFile(contentRef).start
      notifier <- notifyFileComplete(contentRef).start

      _ <- downloader.join
      _ <- notifier.join
    } yield ()
  }

  /*
   * Deferred can be used to avoid busy waiting
   * (This is the main use case)
   */
  private def fileNotifierWithDeferred(): IO[Unit] = {
    // much simpler!
    def notifyFileComplete(signal: Deferred[IO, String]): IO[Unit] =
      for {
        _ <- IO("[notifier] downloading...").debug
        _ <- signal.get // semantically blocks until signal is complete
        _ <- IO("[notifier] file download complete").debug
      } yield ()

    def downloadFilePart(
        part: String,
        contentRef: Ref[IO, String],
        signal: Deferred[IO, String]
    ): IO[Unit] =
      for {
        _ <- IO(s"[downloader] got '$part'").debug
        _ <- IO.sleep(1.second)
        latest <- contentRef.updateAndGet(_ + part)
        _ <-
          if (latest.endsWith("<EOF>"))
            signal.complete(latest).void
          else
            IO.unit
      } yield ()

    for {
      contentRef <- Ref[IO].of("")
      signal <- Deferred[IO, String]

      notifierFib <- notifyFileComplete(signal).start
      fileTasksFib <-
        fileParts
          .map(downloadFilePart(_, contentRef, signal))
          .sequence
          .start

      _ <- notifierFib.join
      _ <- fileTasksFib.join
    } yield ()
  }

  /** Exercises */

  /*
   * 1. Write a small alarm notification with 2 simultaneous IOs:
   *  - One that increments a counter every second (a clock)
   *  - One that waits for the counter to become 10, then prints a message "time's up!"
   */
  private def timer[F[_]: Async](seconds: Int): F[Unit] = {
    def counter(count: Ref[F, Int], signal: Deferred[F, Unit]): F[Unit] =
      for {
        _ <- Async[F].sleep(1.second)
        latest <- count.updateAndGet(_ + 1)
        _ <-
          if (latest >= seconds) signal.complete(()).void
          else counter(count, signal)
      } yield ()

    def notifier(signal: Deferred[F, Unit]): F[Unit] =
      for {
        _ <-
          Async[F]
            .delay(
              s"Setting a timer for $seconds seconds..."
            )
            .debug
        _ <- signal.get
        _ <- Async[F].delay("Time's up!").debug
      } yield ()

    for {
      count <- Ref[F].of(0)
      signal <- Deferred[F, Unit]

      notifierFib <- notifier(signal).start
      counterFib <- counter(count, signal).start

      _ <- notifierFib.join
      _ <- counterFib.join
    } yield ()
  }

  /*
   * 2. Implement racePair with Deferred
   *  - Use a Deferred which can hold an Either[outcome for fa, outcome for fb]
   *  - Start 2 fibers, one for each effect
   *  - On completion (with any status), each IO needs to complete that Deferred
   *  - What do you do in case of cancellation?
   */
  private type RaceResult[F[_], A, B] =
    Either[
      (Outcome[F, Throwable, A], Fiber[F, Throwable, B]),
      (Fiber[F, Throwable, A], Outcome[F, Throwable, B])
    ]

  private type EitherOutcome[F[_], A, B] =
    Either[Outcome[F, Throwable, A], Outcome[F, Throwable, B]]

  private def racePair[F[_]: Async, A, B](
      fa: F[A],
      fb: F[B]
  ): F[RaceResult[F, A, B]] = {
    // CORRECTION: USE .guarantee - MUCH SIMPLER WAY TO DO THIS
    // (this does still work though)
    def handleResult[T](
        fib: Fiber[F, Throwable, T],
        signal: Deferred[F, EitherOutcome[F, A, B]],
        complete: Outcome[F, Throwable, T] => EitherOutcome[F, A, B]
    ): F[Fiber[F, Throwable, T]] =
      for {
        outcome <- fib.join
        _ <- signal.complete(complete(outcome))
      } yield fib

    for {
      signal <- Deferred[F, EitherOutcome[F, A, B]]

      fibA <- fa.start
      fibB <- fb.start

      _ <-
        handleResult[A](
          fibA,
          signal,
          _.asLeft[Outcome[F, Throwable, B]]
        ).start

      _ <-
        handleResult[B](
          fibB,
          signal,
          _.asRight[Outcome[F, Throwable, A]]
        ).start

      /*
       * CORRECTION: Big thing I missed in above exercise:
       * What if the _whole thing_ gets cancelled?
       * THIS call here should be cancellable
       */
      outcome <- signal.get

      result = outcome.map(fibA -> _).leftMap(_ -> fibB)
    } yield result
  }

  // solution from video
  private def betterRacePair[F[_]: Async, A, B](
      fa: F[A],
      fb: F[B]
  ): F[RaceResult[F, A, B]] =
    Async[F].uncancelable { poll =>
      for {
        signal <- Deferred[F, EitherOutcome[F, A, B]]
        fibA <-
          fa.guaranteeCase(outcome =>
            signal.complete(outcome.asLeft[Outcome[F, Throwable, B]]).void
          ).start
        fibB <-
          fb.guaranteeCase(outcome =>
            signal.complete(outcome.asRight[Outcome[F, Throwable, A]]).void
          ).start
        // blocking call -> should be cancelable
        outcome <- poll(signal.get).onCancel {
          // cancel other effects when cancelled
          for {
            cancelFibA <- fibA.cancel.start
            cancelFibB <- fibB.cancel.start
            _ <- cancelFibA.join
            _ <- cancelFibB.join
          } yield ()
        }
        result = outcome.map(fibA -> _).leftMap(_ -> fibB)
      } yield result
    }

  private val ioa = IO.sleep(1.second) >> IO("hello")
  // If a fiber cancels first, that will be returned (same behaviour as the actual IO.racePair)
  private val iob = IO.sleep(500.millis) >> IO.canceled >> IO(2)

  override def run: IO[Unit] = racePair[IO, String, Int](ioa, iob).debug.void
}
