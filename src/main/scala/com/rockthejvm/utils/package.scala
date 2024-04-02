package com.rockthejvm

import cats.effect.Async
import cats.implicits._

package object utils {
  implicit class DebugWrapper[F[_]: Async, A](fa: F[A]) {
    def debug: F[A] = for {
      a <- fa
      t = Thread.currentThread().getName
      _ <- Async[F].delay(println(s"[$t] $a"))
    } yield a
  }
}
