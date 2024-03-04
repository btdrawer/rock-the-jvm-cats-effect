package com.rockthejvm.part1recap

object CatsTypeClasses {
  /*
   * Most important type classes:
   * - Applicative
   * - Functor
   * - FlatMap
   * - Monad
   * - Apply
   * - ApplicativeError/MonadError
   * - Traverse
   */

  /** Functors - Anything which is 'mappable' */
  trait MyFunctor[F[_]] {
    def map[A, B](initialValue: F[A])(f: A => B): F[B]
  }

  import cats.Functor

  // instances for common types
  import cats.instances.list._
  private val listFunctor = Functor[List]

  /*
   * Generalisable 'mapping' APIs
   * i.e., creating mapping functions that can be used in many different types
   * e.g., this creates an 'increment' mapper that could be used for any F[Int]
   */
  def increment[F[_]](container: F[Int])(implicit functor: Functor[F]): F[Int] =
    functor.map(container)(_ + 1)

  // Functor in-scope - cleaner way of doing this
  import cats.syntax.functor._
  def incrementV2[F[_]: Functor](container: F[Int]): F[Int] =
    container.map(_ + 1)

  /** Applicative - Ability to 'wrap' types (extends Functor) */
  trait MyApplicative[F[_]] extends MyFunctor[F] {
    def pure[A](value: A): F[A]
  }

  import cats.Applicative
  private val listApplicative = Applicative[List]
  private val aSimpleList: List[Int] =
    listApplicative.pure(43) // Builds a list of ints, containing just 43

  import cats.syntax.applicative._ // imports the `pure` extension method
  private val aSimpleListV2 = 43.pure[List] // Same as aSimpleList

  /** FlatMap - Ability to chain multiple computations (extends Functor) */
  trait MyFlatMap[F[_]] extends MyFunctor[F] {
    def flatMap[A, B](container: F[A])(f: A => F[B]): F[B]
  }

  import cats.FlatMap
  private val listFlatMap = FlatMap[List]

  import cats.syntax.flatMap._ // flatMap extension method
  def crossProduct[F[_]: FlatMap, A, B](fa: F[A], fb: F[B]): F[(A, B)] =
    for {
      a <- fa
      /*
       * FlatMap extends Functor, so we can use `map` too
       * b is being mapped here, not flat-mapped
       */
      b <- fb
    } yield (a, b)

  /** Monad - Applicative + FlatMap */
  trait MyMonad[F[_]] extends MyApplicative[F] with MyFlatMap[F] {
    override def map[A, B](initialValue: F[A])(f: A => B): F[B] =
      flatMap(initialValue)(a => pure(f(a)))
  }

  import cats.Monad
  private val listMonad = Monad[List]
  def crossProductV2[F[_]: Monad, A, B](fa: F[A], fb: F[B]): F[(A, B)] =
    for {
      a <- fa
      b <- fb
    } yield (a, b)

  /** ApplicativeError - Applicative that might raise an error */
  trait MyApplicativeError[F[_], E] extends Applicative[F] {
    def raiseError[A](e: E): F[A]
  }

  import cats.ApplicativeError
  private type ErrorOr[A] = Either[String, A] // Left is an undesirable value
  /*
   * This apply method works if the second type argument is the same as the Left inside the Either
   * So in this case it has to be String, since the undesirable value from ErrorOr is String
   */
  private val appErrorEither = ApplicativeError[ErrorOr, String]
  private val desirableValue: ErrorOr[Int] = appErrorEither.pure(42)
  private val failedValue: ErrorOr[Int] = appErrorEither.raiseError("error")

  import cats.syntax.applicativeError._ // raiseError extension method
  private val failedValueV2: ErrorOr[Int] = "error".raiseError[ErrorOr, Int]

  /** MonadError - Monad with ApplicativeError */
  trait MyMonadError[F[_], E] extends MyApplicativeError[F, E] with Monad[F]

  import cats.MonadError
  private val monadErrorEither = MonadError[ErrorOr, String]

  /** Traverse - Turn nested wrappers inside out */
  trait MyTraverse[F[_]] extends MyFunctor[F] {
    def traverse[G[_], A, B](fa: F[A])(f: A => G[B]): G[F[B]]
  }

  private val listOfOptions: List[Option[Int]] =
    List(
      Some(1),
      Some(2),
      Some(43)
    ) // cumbersome, better to have Option[List[Int]]

  import cats.Traverse
  private val listTraverse = Traverse[List]
  private val optionList: Option[List[Int]] =
    listTraverse.traverse(List(1, 2, 3))(
      Option(_)
    ) // traverse creates Option[List[Int]] here

  import cats.syntax.traverse._
  private val optionListV2: Option[List[Int]] =
    List(1, 2, 3).traverse(Option(_))

  def main(args: Array[String]): Unit = {}
}
