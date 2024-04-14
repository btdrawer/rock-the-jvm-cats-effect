package com.rockthejvm.utils

import cats.Functor
import cats.implicits._

package object general {
  implicit class DebugWrapper[F[_], A](fa: F[A]) {
    def debug(implicit func: Functor[F]): F[A] = for {
      a <- fa
      t = Thread.currentThread().getName
      _ = println(s"[$t] $a")
    } yield a
  }
}
