package example

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import spray.can.Http
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._

object Boot extends App {

  implicit val system = ActorSystem("on-spray-can")

  val service = system.actorOf(Props[MyServiceActor], "demo-service")

  implicit val timeout = Timeout(30.seconds)
  IO(Http) ? Http.Bind(service, interface = "localhost", port = args(0).toInt)
}
