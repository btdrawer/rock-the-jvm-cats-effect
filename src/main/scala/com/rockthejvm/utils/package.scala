package com.rockthejvm

import cats.effect.IO

package object utils {
  implicit class DebugWrapper[A](ioa: IO[A]) {
    def debug: IO[A] = for {
      a <- ioa
      t = Thread.currentThread().getName
      _ = println(s"[$t] $a")
    } yield a
  }
}
