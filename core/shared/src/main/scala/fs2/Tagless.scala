package fs2.tagless

import fs2.{Chunk, CompositeFailure, Fallible, INothing, Pure, RaiseThrowable}
import fs2.internal.{CompileScope, Token}

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.implicits._

import scala.collection.generic.CanBuildFrom

sealed trait Pull[+F[_], +O, +R] {

  def attempt: Pull[F, O, Either[Throwable, R]] =
    map(r => Right(r): Either[Throwable, R]).handleErrorWith(t =>
      Pull.pure(Left(t): Either[Throwable, R]))

  def compile[F2[x] >: F[x], R2 >: R, S](initial: S)(f: (S, Chunk[O]) => S)(
      implicit F: Sync[F2]): F2[(S, R2)] =
    F.delay(CompileScope.newRoot[F2])
      .bracketCase(scope => compileScope[F2, R2, S](scope, initial)(f))((scope, ec) =>
        scope.close(ec).rethrow)

  private def compileScope[F2[x] >: F[x]: Sync, R2 >: R, S](scope: CompileScope[F2], initial: S)(
      f: (S, Chunk[O]) => S): F2[(S, R2)] =
    step[F2, O, R2](scope).flatMap {
      case Right((hd, tl)) => tl.compileScope[F2, R2, S](scope, f(initial, hd))(f)
      case Left(r)         => (initial, r: R2).pure[F2]
    }

  private[fs2] def step[F2[x] >: F[x]: Sync, O2 >: O, R2 >: R](
      scope: CompileScope[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]]

  def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R]

  def flatMap[F2[x] >: F[x], R2, O2 >: O](f: R => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    new Pull.FlatMap[F2, O2, R, R2](this, f)

  final def >>[F2[x] >: F[x], O2 >: O, R2 >: R](that: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    flatMap(_ => that)

  /** If `this` terminates with `Pull.raiseError(e)`, invoke `h(e)`. */
  def handleErrorWith[F2[x] >: F[x], O2 >: O, R2 >: R](
      f: Throwable => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    new Pull.HandleErrorWith(this, f)

  def map[R2](f: R => R2): Pull[F, O, R2] = flatMap(r => Pull.pure(f(r)))

}

object Pull {

  val done: Pull[Pure, INothing, Unit] = pure(())

  def pure[F[x] >: Pure[x], R](r: R): Pull[F, INothing, R] = new Result[R](r)

  private[fs2] final class Result[R](r: R) extends Pull[Pure, INothing, R] {
    private[fs2] def step[F2[x] >: Pure[x], O2 >: INothing, R2 >: R](scope: CompileScope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      F.pure(Either.left(r))
    def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, INothing, R] = this
  }

  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] = new Output(Chunk.singleton(o))

  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[F, O, Unit] =
    if (os.isEmpty) done else new Output(os)

  private final class Output[O](os: Chunk[O]) extends Pull[Pure, O, Unit] {
    private[fs2] def step[F2[x] >: Pure[x], O2 >: O, R2 >: Unit](scope: CompileScope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      F.pure(Right((os, done)))
    def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, O, Unit] = this
  }

  def eval[F[_], R](fr: F[R]): Pull[F, INothing, R] = new Eval(fr)

  private final class Eval[F[_], R](fr: F[R]) extends Pull[F, INothing, R] {
    private[fs2] def step[F2[x] >: F[x]: Sync, O2 >: INothing, R2 >: R](
        scope: CompileScope[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      (fr: F2[R]).map(Left(_))
    def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, R] =
      eval(f(fr))
  }

  private final class FlatMap[F[_], O, R0, R](source: Pull[F, O, R0], g: R0 => Pull[F, O, R])
      extends Pull[F, O, R] {

    private[fs2] def step[F2[x] >: F[x]: Sync, O2 >: O, R2 >: R](
        scope: CompileScope[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      source.step(scope).flatMap {
        case Right((hd, tl)) =>
          (Right((hd, tl.flatMap(g))): Either[R2, (Chunk[O2], Pull[F2, O2, R2])]).pure[F2]
        case Left(r) => g(r).step(scope)
      }

    def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R] =
      source.translate(f).flatMap(r => g(r).translate(f))
  }

  private final class HandleErrorWith[F[_], O, R](source: Pull[F, O, R],
                                                  h: Throwable => Pull[F, O, R])
      extends Pull[F, O, R] {

    private[fs2] def step[F2[x] >: F[x]: Sync, O2 >: O, R2 >: R](
        scope: CompileScope[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      source.step[F2, O2, R2](scope).handleErrorWith(t => h(t).step(scope))

    def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R] =
      source.translate(f).handleErrorWith(t => h(t).translate(f))
  }

  def raiseError[F[_]: RaiseThrowable](err: Throwable): Pull[F, INothing, INothing] =
    new RaiseError(err)

  private[fs2] def raiseErrorForce[F[_]](err: Throwable): Pull[F, INothing, INothing] =
    new RaiseError(err)

  private final class RaiseError[F[_]](err: Throwable) extends Pull[F, INothing, INothing] {
    private[fs2] def step[F2[x] >: F[x], O2 >: INothing, R2 >: INothing](scope: CompileScope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      F.raiseError(err)

    def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, INothing] =
      new RaiseError[G](err)
  }

  def mapConcat[F[_], O, O2](p: Pull[F, O, Unit])(f: O => Pull[F, O2, Unit]): Pull[F, O2, Unit] =
    new MapConcat(p, f)

  private final class MapConcat[F[_], O, O2](source: Pull[F, O, Unit], g: O => Pull[F, O2, Unit])
      extends Pull[F, O2, Unit] {

    private[fs2] def step[F2[x] >: F[x]: Sync, O3 >: O2, R2 >: Unit](
        scope: CompileScope[F2]): F2[Either[R2, (Chunk[O3], Pull[F2, O3, R2])]] =
      source.step(scope).flatMap {
        case Right((hd, tl)) =>
          tl match {
            case _: Result[_] if hd.size == 1 =>
              // nb: If tl is Pure, there's no need to propagate flatMap through the tail. Hence, we
              // check if hd has only a single element, and if so, process it directly instead of folding.
              // This allows recursive infinite streams of the form `def s: Stream[Pure,O] = Stream(o).flatMap { _ => s }`
              g(hd(0)).step(scope)
            case _ =>
              def go(idx: Int): Pull[F2, O2, Unit] =
                if (idx == hd.size) mapConcat(tl)(g)
                else g(hd(idx)) >> go(idx + 1) // TODO: handle interruption specifics here
              go(0).step(scope)
          }
        case Left(()) => Either.left[R2, (Chunk[O3], Pull[F2, O3, R2])](()).pure[F2]
      }

    def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O2, Unit] =
      new MapConcat[G, O, O2](source.translate(f), o => g(o).translate(f))
  }

  private[fs2] def acquireWithToken[F[_], R](
      resource: F[R],
      release: (R, ExitCase[Throwable]) => F[Unit]): Pull[F, INothing, (R, Token)] =
    new Acquire(resource, release)

  private final class Acquire[F[_], R](resource: F[R], release: (R, ExitCase[Throwable]) => F[Unit])
      extends Pull[F, INothing, (R, Token)] {

    private[fs2] def step[F2[x] >: F[x], O2 >: INothing, R2 >: (R, Token)](scope: CompileScope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      scope.acquireResource(resource, release).flatMap {
        case Right(rt) => F.pure(Left(rt))
        case Left(t)   => F.raiseError(t)
      }

    def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, (R, Token)] =
      new Acquire[G, R](f(resource), (r, ec) => f(release(r, ec)))
  }

  private[fs2] def release[F[x] >: Pure[x]](token: Token,
                                            err: Option[Throwable]): Pull[F, INothing, Unit] =
    new Release(token, err)

  private final class Release(token: Token, err: Option[Throwable])
      extends Pull[Pure, INothing, Unit] {

    private[fs2] def step[F2[x] >: Pure[x], O2 >: INothing, R2 >: Unit](scope: CompileScope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      scope.releaseResource(token, err.map(ExitCase.error).getOrElse(ExitCase.Completed)).flatMap {
        case Right(_) => F.pure(Left(()))
        case Left(t)  => F.raiseError(t)
      }

    def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, INothing, Unit] = this
  }

  implicit def monadInstance[F[_], O]: Monad[Pull[F, O, ?]] =
    new Monad[Pull[F, O, ?]] {
      def pure[A](a: A): Pull[F, O, A] = Pull.pure(a)
      def flatMap[A, B](p: Pull[F, O, A])(f: A => Pull[F, O, B]) = p.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Pull[F, O, Either[A, B]]) =
        f(a).flatMap {
          case Left(a)  => tailRecM(a)(f)
          case Right(b) => Pull.pure(b)
        }
    }
}

final class Stream[+F[_], +O] private (private val asPull: Pull[F, O, Unit]) extends AnyVal {

  /**
    * Appends `s2` to the end of this stream.
    * @example {{{
    * scala> ( Stream(1,2,3)++Stream(4,5,6) ).toList
    * res0: List[Int] = List(1, 2, 3, 4, 5, 6)
    * }}}
    *
    * If `this` stream is not terminating, then the result is equivalent to `this`.
    */
  def ++[F2[x] >: F[x], O2 >: O](s2: => Stream[F2, O2]): Stream[F2, O2] = append(s2)

  /** Appends `s2` to the end of this stream. Alias for `s1 ++ s2`. */
  def append[F2[x] >: F[x], O2 >: O](s2: => Stream[F2, O2]): Stream[F2, O2] =
    Stream.fromPull(asPull >> s2.asPull)

  def compile[F2[x] >: F[x], G[_], O2 >: O](
      implicit compiler: Stream.Compiler[F2, G]): Stream.CompileOps[F2, G, O2] =
    new Stream.CompileOps[F2, G, O2](asPull)

  def covary[F2[x] >: F[x]]: Stream[F2, O] = this

  def flatMap[F2[x] >: F[x], O2](f: O => Stream[F2, O2]): Stream[F2, O2] =
    Stream.fromPull(Pull.mapConcat(asPull: Pull[F2, O, Unit])(o => f(o).asPull))

  /**
    * If `this` terminates with `Stream.raiseError(e)`, invoke `h(e)`.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).append(Stream.raiseError[cats.effect.IO](new RuntimeException)).handleErrorWith(t => Stream(0)).compile.toList.unsafeRunSync()
    * res0: List[Int] = List(1, 2, 3, 0)
    * }}}
    */
  def handleErrorWith[F2[x] >: F[x], O2 >: O](h: Throwable => Stream[F2, O2]): Stream[F2, O2] =
    Stream.fromPull(asPull.handleErrorWith(e => h(e).asPull)) // TODO Insert scope?

  def translate[F2[x] >: F[x], G[_]](u: F2 ~> G): Stream[G, O] =
    Stream.fromPull(asPull.translate(u))
}

object Stream {
  private[fs2] def fromPull[F[_], O](p: Pull[F, O, Unit]): Stream[F, O] = new Stream(p)

  def apply[O](os: O*): Stream[Pure, O] = emits(os)

  /**
    * Creates a stream that emits a resource allocated by an effect, ensuring the resource is
    * eventually released regardless of how the stream is used.
    *
    * A typical use case for bracket is working with files or network sockets. The resource effect
    * opens a file and returns a reference to it. One can then flatMap on the returned Stream to access
    *  the file, e.g to read bytes and transform them in to some stream of elements
    * (e.g., bytes, strings, lines, etc.).
    * The `release` action then closes the file once the result Stream terminates, even in case of interruption
    * or errors.
    *
    * @param acquire resource to acquire at start of stream
    * @param release function which returns an effect that releases the resource
    */
  def bracket[F[x] >: Pure[x], R](acquire: F[R])(release: R => F[Unit]): Stream[F, R] =
    bracketCase(acquire)((r, _) => release(r))

  /**
    * Like [[bracket]] but the release action is passed an `ExitCase[Throwable]`.
    *
    * `ExitCase.Canceled` is passed to the release action in the event of either stream interruption or
    * overall compiled effect cancelation.
    */
  def bracketCase[F[x] >: Pure[x], R](acquire: F[R])(
      release: (R, ExitCase[Throwable]) => F[Unit]): Stream[F, R] =
    Stream.fromPull(Pull.acquireWithToken(acquire, release).flatMap {
      case (r, token) =>
        Pull.output1(r).attempt.flatMap {
          case Right(_) => Pull.release(token, None)
          case Left(err) =>
            Pull.release(token, Some(err)).attempt.flatMap {
              case Right(_) => Pull.raiseErrorForce(err)
              case Left(err2) =>
                if (!err.eq(err2)) Pull.raiseErrorForce(CompositeFailure(err, err2))
                else Pull.raiseErrorForce(err)
            }
        }
    })

  def chunk[O](c: Chunk[O]): Stream[Pure, O] = fromPull(Pull.output(c))

  def emit[O](o: O): Stream[Pure, O] = fromPull(Pull.output1(o))

  def emits[O](os: Seq[O]): Stream[Pure, O] =
    if (os.isEmpty) empty
    else if (os.size == 1) emit(os.head)
    else fromPull(Pull.output(Chunk.seq(os)))

  val empty: Stream[Pure, Nothing] = fromPull(Pull.done)

  def eval[F[_], O](fo: F[O]): Stream[F, O] =
    fromPull(Pull.eval(fo).flatMap(o => Pull.output1(o)))

  def raiseError[F[_]: RaiseThrowable](e: Throwable): Stream[F, INothing] =
    fromPull(Pull.raiseError(e))

  /** Provides syntax for pure streams. */
  implicit def PureOps[O](s: Stream[Pure, O]): PureOps[O] = new PureOps(s.asPull)

  /** Provides syntax for pure streams. */
  final class PureOps[O] private[Stream] (private val asPull: Pull[Pure, O, Unit]) extends AnyVal {
    private def self: Stream[Pure, O] = Stream.fromPull(asPull)

    /** Alias for covary, to be able to write `Stream.empty[X]`. */
    def apply[F[_]]: Stream[F, O] = covary

    /** Lifts this stream to the specified effect type. */
    def covary[F[_]]: Stream[F, O] = self

    /** Runs this pure stream and returns the emitted elements in a collection of the specified type. Note: this method is only available on pure streams. */
    def to[C[_]](implicit cbf: CanBuildFrom[Nothing, O, C[O]]): C[O] =
      self.covary[IO].compile.to[C].unsafeRunSync

    /** Runs this pure stream and returns the emitted elements in a chunk. Note: this method is only available on pure streams. */
    def toChunk: Chunk[O] = self.covary[IO].compile.toChunk.unsafeRunSync

    /** Runs this pure stream and returns the emitted elements in a list. Note: this method is only available on pure streams. */
    def toList: List[O] = self.covary[IO].compile.toList.unsafeRunSync

    /** Runs this pure stream and returns the emitted elements in a vector. Note: this method is only available on pure streams. */
    def toVector: Vector[O] = self.covary[IO].compile.toVector.unsafeRunSync
  }

  /** Provides syntax for streams with effect type `cats.Id`. */
  implicit def IdOps[O](s: Stream[Id, O]): IdOps[O] = new IdOps(s.asPull)

  /** Provides syntax for pure pipes based on `cats.Id`. */
  final class IdOps[O] private[Stream] (private val asPull: Pull[Id, O, Unit]) extends AnyVal {
    private def self: Stream[Id, O] = Stream.fromPull(asPull)

    private def idToApplicative[F[_]: Applicative]: Id ~> F =
      new (Id ~> F) { def apply[A](a: Id[A]) = a.pure[F] }

    def covaryId[F[_]: Applicative]: Stream[F, O] = self.translate(idToApplicative[F])
  }

  /** Provides syntax for streams with effect type `Fallible`. */
  implicit def FallibleOps[O](s: Stream[Fallible, O]): FallibleOps[O] =
    new FallibleOps(s.asPull)

  /** Provides syntax for fallible streams. */
  final class FallibleOps[O] private[Stream] (private val asPull: Pull[Fallible, O, Unit])
      extends AnyVal {
    private def self: Stream[Fallible, O] = Stream.fromPull(asPull)

    /** Lifts this stream to the specified effect type. */
    def lift[F[_]](implicit F: ApplicativeError[F, Throwable]): Stream[F, O] = {
      val _ = F
      self.asInstanceOf[Stream[F, O]]
    }

    /** Runs this fallible stream and returns the emitted elements in a collection of the specified type. Note: this method is only available on fallible streams. */
    def to[C[_]](implicit cbf: CanBuildFrom[Nothing, O, C[O]]): Either[Throwable, C[O]] =
      lift[IO].compile.to[C].attempt.unsafeRunSync

    /** Runs this fallible stream and returns the emitted elements in a chunk. Note: this method is only available on fallible streams. */
    def toChunk: Either[Throwable, Chunk[O]] = lift[IO].compile.toChunk.attempt.unsafeRunSync

    /** Runs this fallible stream and returns the emitted elements in a list. Note: this method is only available on fallible streams. */
    def toList: Either[Throwable, List[O]] = lift[IO].compile.toList.attempt.unsafeRunSync

    /** Runs this fallible stream and returns the emitted elements in a vector. Note: this method is only available on fallible streams. */
    def toVector: Either[Throwable, Vector[O]] =
      lift[IO].compile.toVector.attempt.unsafeRunSync
  }

  /** Type class which describes compilation of a `Stream[F, O]` to a `G[?]`. */
  sealed trait Compiler[F[_], G[_]] {
    private[Stream] def apply[O, B, C](s: Stream[F, O], init: Eval[B])(fold: (B, Chunk[O]) => B,
                                                                       finalize: B => C): G[C]
  }

  object Compiler {
    implicit def syncInstance[F[_]](implicit F: Sync[F]): Compiler[F, F] = new Compiler[F, F] {
      def apply[O, B, C](s: Stream[F, O], init: Eval[B])(foldChunk: (B, Chunk[O]) => B,
                                                         finalize: B => C): F[C] =
        F.delay(init.value).flatMap(i => s.asPull.compile(i)(foldChunk)).map {
          case (b, _) => finalize(b)
        }
    }

    implicit val pureInstance: Compiler[Pure, Id] = new Compiler[Pure, Id] {
      def apply[O, B, C](s: Stream[Pure, O], init: Eval[B])(foldChunk: (B, Chunk[O]) => B,
                                                            finalize: B => C): C =
        finalize(s.covary[IO].asPull.compile(init.value)(foldChunk).unsafeRunSync._1)
    }

    implicit val idInstance: Compiler[Id, Id] = new Compiler[Id, Id] {
      def apply[O, B, C](s: Stream[Id, O], init: Eval[B])(foldChunk: (B, Chunk[O]) => B,
                                                          finalize: B => C): C =
        finalize(s.covaryId[IO].asPull.compile(init.value)(foldChunk).unsafeRunSync._1)
    }

    implicit val fallibleInstance: Compiler[Fallible, Either[Throwable, ?]] =
      new Compiler[Fallible, Either[Throwable, ?]] {
        def apply[O, B, C](s: Stream[Fallible, O], init: Eval[B])(
            foldChunk: (B, Chunk[O]) => B,
            finalize: B => C): Either[Throwable, C] =
          s.lift[IO].asPull.compile(init.value)(foldChunk).attempt.unsafeRunSync.map {
            case (b, _) => finalize(b)
          }
      }
  }

  /** Projection of a `Stream` providing various ways to compile a `Stream[F,O]` to an `F[...]`. */
  final class CompileOps[F[_], G[_], O] private[Stream] (private val self: Pull[F, O, Unit])(
      implicit compiler: Compiler[F, G]) {

    /**
      * Compiles this stream in to a value of the target effect type `F` and
      * discards any output values of the stream.
      *
      * To access the output values of the stream, use one of the other compilation methods --
      * e.g., [[fold]], [[toVector]], etc.
      */
    def drain: G[Unit] = foldChunks(())((_, _) => ())

    /**
      * Compiles this stream in to a value of the target effect type `F` by folding
      * the output values together, starting with the provided `init` and combining the
      * current value with each output value.
      */
    def fold[B](init: B)(f: (B, O) => B): G[B] =
      foldChunks(init)((acc, c) => c.foldLeft(acc)(f))

    /**
      * Compiles this stream in to a value of the target effect type `F` by folding
      * the output chunks together, starting with the provided `init` and combining the
      * current value with each output chunk.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      */
    def foldChunks[B](init: B)(f: (B, Chunk[O]) => B): G[B] =
      compiler(fromPull(self), Eval.now(init))(f, identity)

    /**
      * Like [[fold]] but uses the implicitly available `Monoid[O]` to combine elements.
      *
      * @example {{{
      * scala> import cats.implicits._, cats.effect.IO
      * scala> Stream(1, 2, 3, 4, 5).covary[IO].compile.foldMonoid.unsafeRunSync
      * res0: Int = 15
      * }}}
      */
    def foldMonoid(implicit O: Monoid[O]): G[O] =
      fold(O.empty)(O.combine)

    /**
      * Like [[fold]] but uses the implicitly available `Semigroup[O]` to combine elements.
      * If the stream emits no elements, `None` is returned.
      *
      * @example {{{
      * scala> import cats.implicits._, cats.effect.IO
      * scala> Stream(1, 2, 3, 4, 5).covary[IO].compile.foldSemigroup.unsafeRunSync
      * res0: Option[Int] = Some(15)
      * scala> Stream.empty.covaryAll[IO, Int].compile.foldSemigroup.unsafeRunSync
      * res1: Option[Int] = None
      * }}}
      */
    def foldSemigroup(implicit O: Semigroup[O]): G[Option[O]] =
      fold(Option.empty[O])((acc, o) => acc.map(O.combine(_, o)).orElse(Some(o)))

    /**
      * Compiles this stream in to a value of the target effect type `F`,
      * returning `None` if the stream emitted no values and returning the
      * last value emitted wrapped in `Some` if values were emitted.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.last.unsafeRunSync
      * res0: Option[Int] = Some(4)
      * }}}
      */
    def last: G[Option[O]] =
      foldChunks(Option.empty[O])((acc, c) => c.last.orElse(acc))

    /**
      * Compiles this stream in to a value of the target effect type `F`,
      * raising a `NoSuchElementException` if the stream emitted no values
      * and returning the last value emitted otherwise.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.lastOrError.unsafeRunSync
      * res0: Int = 4
      * scala> Stream.empty.covaryAll[IO, Int].compile.lastOrError.attempt.unsafeRunSync
      * res1: Either[Throwable, Int] = Left(java.util.NoSuchElementException)
      * }}}
      */
    def lastOrError(implicit G: MonadError[G, Throwable]): G[O] =
      last.flatMap(_.fold(G.raiseError(new NoSuchElementException): G[O])(G.pure))

    /**
      * Compiles this stream into a value of the target effect type `F` by logging
      * the output values to a `C`, given a `CanBuildFrom`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.to[List].unsafeRunSync
      * res0: List[Int] = List(0, 1, 2, 3, 4)
      * }}}
      */
    def to[C[_]](implicit cbf: CanBuildFrom[Nothing, O, C[O]]): G[C[O]] =
      compiler(Stream.fromPull(self), Eval.always(cbf()))(_ ++= _.iterator, _.result)

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `Chunk`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toChunk.unsafeRunSync
      * res0: Chunk[Int] = Chunk(0, 1, 2, 3, 4)
      * }}}
      */
    def toChunk: G[Chunk[O]] =
      compiler(Stream.fromPull(self), Eval.always(List.newBuilder[Chunk[O]]))(
        _ += _,
        bldr => Chunk.concat(bldr.result))

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `List`. Equivalent to `to[List]`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toList.unsafeRunSync
      * res0: List[Int] = List(0, 1, 2, 3, 4)
      * }}}
      */
    def toList: G[List[O]] =
      to[List]

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `Vector`. Equivalent to `to[Vector]`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toVector.unsafeRunSync
      * res0: Vector[Int] = Vector(0, 1, 2, 3, 4)
      * }}}
      */
    def toVector: G[Vector[O]] =
      to[Vector]
  }
}
