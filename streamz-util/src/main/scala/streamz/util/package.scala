package streamz

import scala.concurrent.ExecutionContext

import scalaz._
import Scalaz._

import scalaz.concurrent.Task

package object util {
  implicit def scalaFuture2scalazTask[T](sf: scala.concurrent.Future[T])(implicit ec: ExecutionContext): Task[T] = {
    Task.async { cb =>
      sf.onComplete {
        case scala.util.Success(v) => cb(v.right)
        case scala.util.Failure(e) => cb(e.left)
      }
    }
  }
}
