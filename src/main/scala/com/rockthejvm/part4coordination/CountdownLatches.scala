package com.rockthejvm.part4coordination

import cats.{ApplicativeError, Monad, MonadThrow, Parallel}
import cats.effect.implicits.{genSpawnOps, monadCancelOps_}
import cats.effect.kernel.{Async, Outcome}
import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.std.CountDownLatch
import cats.effect.{Deferred, IO, IOApp, Resource}
import cats.implicits._
import com.rockthejvm.utils.DebugWrapper

import java.io.{File, FileWriter}
import scala.concurrent.duration.DurationInt
import scala.io.Source

object CountdownLatches extends IOApp.Simple {

  /** A countdown latch is a coordination primitive initialised with a count
    * and 2 methods:
    * - await
    * - release
    *
    * All fibers calling `await` are semantically blocked
    * We can decrease the count via `release`
    * Once the count reaches 0, all fibers are unblocked
    */

  private def announcer[F[_]](
      latch: CountDownLatch[F]
  )(implicit A: Async[F]): F[Unit] =
    for {
      _ <- A.pure("starting race shortly...").debug >> A.sleep(2.seconds)

      _ <- A.pure(s"5...").debug >> A.sleep(1.second)
      _ <- latch.release
      _ <- A.pure(s"4...").debug >> A.sleep(1.second)
      _ <- latch.release
      _ <- A.pure(s"3...").debug >> A.sleep(1.second)
      _ <- latch.release
      _ <- A.pure(s"2...").debug >> A.sleep(1.second)
      _ <- latch.release
      _ <- A.pure(s"1...").debug >> A.sleep(1.second)
      _ <- latch.release

      _ <- A.pure("go!").debug
    } yield ()

  private def runner[F[_]: Async](id: Int, latch: CountDownLatch[F]): F[Unit] =
    for {
      _ <- s"[runner $id] waiting for signal...".pure[F].debug
      _ <- latch.await // blocked until count reaches 0
      _ <- s"[runner $id] running!".pure[F].debug
    } yield ()

  private def sprint[F[_]: Async: Parallel]: F[Unit] =
    for {
      latch <- CountDownLatch[F](5)
      announcerFib <- announcer[F](latch).start
      _ <- (1 to 10).toList.parTraverse(runner(_, latch))
      _ <- announcerFib.join
    } yield ()

  /** Exercise: Simulate a file downloader on multiple threads */
  object FileServer {
    private val fileChunksList = List(
      "I love Scala",
      "Cats Effect seems good",
      "Never would I have thought I would do low-level concurrency WITH pure FP"
    )

    def getNumberOfChunks[F[_]: Monad]: F[Int] =
      fileChunksList.length.pure[F]
    def getFileChunk[F[_]: Monad](n: Int): F[String] =
      fileChunksList(n).pure[F]
  }

  private def writeToFile[F[_]](path: String, contents: String)(implicit
      A: Async[F]
  ): F[Unit] = {
    val fileResource = Resource.make[F, FileWriter](
      A.delay(
        new FileWriter(new File(path))
      )
    )(writer => A.delay(writer.close()))

    fileResource.use { writer =>
      A.delay(writer.write(contents))
    }
  }

  private def appendFileContents[F[_]](
      fromPath: String,
      toPath: String
  )(implicit A: Async[F]): F[Unit] = {
    val compositeResource =
      for {
        reader <-
          Resource.make(
            A.delay(
              Source.fromFile(fromPath)
            )
          )(source => A.delay(source.close()))
        writer <-
          Resource.make(
            A.delay(
              new FileWriter(new File(toPath), true)
            )
          )(writer => A.delay(writer.close()))
      } yield reader -> writer

    compositeResource.use { case (reader, writer) =>
      A.delay(reader.getLines().foreach(writer.write))
    }
  }

  /*
   * Exercise:
   * - Call a file server API and ge the number of chunks (n)
   * - Start a CDLatch
   * - Start n fibers which download a chunk of the file
   * - Block on the latch until each task has finished
   * - After all chunks are done, stitch them together under the same file on disk
   */
  private def downloadFile[F[_]](filename: String, destFolder: String)(implicit
      A: Async[F],
      P: Parallel[F]
  ): F[Unit] =
    for {
      numberOfChunks <- FileServer.getNumberOfChunks[F]
      latch <- CustomCountDownLatch.create[F](numberOfChunks)
      pathsFib <-
        (0 until numberOfChunks).toList
          .parTraverse(
            writeChunkToFile(filename, destFolder, latch)
          )
          .start
      toPath <- s"$destFolder/$filename".pure[F]
      _ <- writeToFile[F](toPath, "")
      _ <- latch.await
      fromPaths <- pathsFib.join.value
      _ <- addChunksToMainFile(fromPaths, toPath)
    } yield ()

  /*
   * ^ What I don't understand about the above (and the video solution
   * is not that different) is what the latch is adding here?
   *
   * Could we not just join on pathsFib to get the same effect?
   */

  private def writeChunkToFile[F[_]: Async](
      filename: String,
      destFolder: String,
      latch: CustomCountDownLatch[F]
  )(i: Int): F[String] =
    for {
      chunk <- FileServer.getFileChunk[F](i)
      path <- s"$destFolder/$filename-chunk-$i".pure[F]
      _ <- writeToFile(path, chunk)
      _ <- latch.release
    } yield path

  private def addChunksToMainFile[F[_]: Async](
      fromPaths: List[String],
      toPath: String
  ): F[Unit] = {
    fromPaths.foldLeft(().pure[F]) { (fa, fromPath) =>
      for {
        _ <- fa
        _ <- appendFileContents[F](fromPath, toPath)
      } yield ()
    }
  }

  private implicit class OutcomeExt[F[_], E, A](
      val outcome: F[Outcome[F, E, A]]
  ) extends AnyVal {
    def value(implicit MT: MonadThrow[F], AE: ApplicativeError[F, E]): F[A] =
      outcome.flatMap {
        case Succeeded(fa) =>
          fa
        case Errored(e) =>
          e.raiseError[F, A]
        case Canceled() =>
          new Exception("Fiber was cancelled").raiseError[F, A]
      }
  }

  override def run: IO[Unit] =
    for {
      desktop <- s"${System.getProperty("user.home")}/Desktop".pure[IO]
      _ <- downloadFile[IO]("hello", desktop)
    } yield ()
}

/** Exercise: Build your own CountDownLatch with Ref and Deferred */

trait CustomCountDownLatch[F[_]] {
  def await: F[Unit]
  def release: F[Unit]
}

object CustomCountDownLatch {
  def create[F[_]](n: Int)(implicit A: Async[F]): F[CustomCountDownLatch[F]] =
    for {
      counterRef <- A.ref[Int](n)
      finishedSignal <- A.deferred[Unit]
    } yield new CustomCountDownLatch[F] {
      override def await: F[Unit] =
        finishedSignal.get

      override def release: F[Unit] =
        counterRef.modify { counter =>
          val newCounter = counter - 1
          newCounter -> maybeCompleteSignal(newCounter)
        }.flatten

      private def maybeCompleteSignal(counter: Int): F[Unit] =
        if (counter < 1) finishedSignal.complete(()).void
        else A.unit
    }
}

/*
 * My solution was more lo-fi than the video solution, but still works.
 *
 * Still, here's the video solution:
 */

object CustomCountDownLatchSolution {
  private sealed trait State[F[_]]
  private final case class Done[F[_]]() extends State[F]
  private final case class Live[F[_]](n: Int, signal: Deferred[F, Unit])
      extends State[F]

  def create[F[_]](n: Int)(implicit A: Async[F]): F[CustomCountDownLatch[F]] =
    for {
      signal <- A.deferred[Unit]
      stateRef <- A.ref[State[F]](Live(n, signal))
    } yield new CustomCountDownLatch[F] {
      override def await: F[Unit] =
        stateRef.get.flatMap {
          case Done() => A.unit
          case _      => signal.get
        }

      override def release: F[Unit] =
        stateRef
          .modify {
            case Done()          => Done[F]() -> A.unit
            case Live(1, signal) => Done[F]() -> signal.complete(()).void
            case Live(n, signal) => Live(n - 1, signal) -> A.unit
          }
          .flatten
          .uncancelable
    }
}
