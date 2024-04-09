package com.rockthejvm

import cats.effect.Sync
import cats.implicits._

package object utils {
  implicit class DebugWrapper[F[_]: Sync, A](fa: F[A]) {
    def debug: F[A] = for {
      a <- fa
      t = Thread.currentThread().getName
      _ <- Sync[F].delay(println(s"[$t] $a"))
    } yield a
  }
}
