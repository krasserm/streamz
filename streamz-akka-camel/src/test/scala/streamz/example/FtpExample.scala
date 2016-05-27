package streamz.example

import java.io.InputStream

import akka.actor.ActorSystem

import scalaz.concurrent.Task
import scalaz.stream._

import streamz.akka.camel._

object FtpExample extends App {
  implicit val system = ActorSystem("example")

  // FTP server endpoint accessed via camel-ftp (see also
  // http://camel.apache.org/components.html for a complete
  // list of configurable endpoints).
  val enpointUri ="ftp://ftp.example.com?antInclude=*.txt&idempotent=true"

  val ftpLines: Process[Task,String] = for {
    // receive existing (and new) *.txt files from server
    is  <- receive[InputStream](enpointUri)
    // split each file into lines
    line <- io.linesR(is)
  } yield line

  val printUpper: Process[Task,Unit] = ftpLines
    // convert lines to upper case
    .map(_.toUpperCase)
    // write lines from all files to stdout
    .to(io.stdOutLines)

  // side effects here ...
  printUpper.run.unsafePerformSync


  // To process files from a local directory, change the enpointUri to
  // "file:testdata?noop=true". After having started the process, add new text
  // files to the testdata directory and they will be automatically processed.
}
