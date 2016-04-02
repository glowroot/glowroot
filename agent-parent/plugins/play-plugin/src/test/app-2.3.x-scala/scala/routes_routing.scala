// @SOURCE:D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/conf/routes
// @HASH:b89ea907125d831ceb1684540366e73d815da76f
// @DATE:Sat Apr 09 16:08:52 PDT 2016


import scala.language.reflectiveCalls
import play.core._
import play.core.Router._
import play.core.Router.HandlerInvokerFactory._
import play.core.j._

import play.api.mvc._
import _root_.controllers.Assets.Asset

import Router.queryString

object Routes extends Router.Routes {

import ReverseRouteContext.empty

private var _prefix = "/"

def setPrefix(prefix: String): Unit = {
  _prefix = prefix
  List[(String,Routes)]().foreach {
    case (p, router) => router.setPrefix(prefix + (if(prefix.endsWith("/")) "" else "/") + p)
  }
}

def prefix = _prefix

lazy val defaultPrefix = { if(Routes.prefix.endsWith("/")) "" else "/" }


// @LINE:5
private[this] lazy val controllers_HomeController_index0_route = Route("GET", PathPattern(List(StaticPart(Routes.prefix))))
private[this] lazy val controllers_HomeController_index0_invoker = createInvoker(
controllers.HomeController.index,
HandlerDef(this.getClass.getClassLoader, "", "controllers.HomeController", "index", Nil,"GET", """""", Routes.prefix + """"""))
        

// @LINE:6
private[this] lazy val controllers_AsyncController_message1_route = Route("GET", PathPattern(List(StaticPart(Routes.prefix),StaticPart(Routes.defaultPrefix),StaticPart("message"))))
private[this] lazy val controllers_AsyncController_message1_invoker = createInvoker(
controllers.AsyncController.message,
HandlerDef(this.getClass.getClassLoader, "", "controllers.AsyncController", "message", Nil,"GET", """""", Routes.prefix + """message"""))
        

// @LINE:7
private[this] lazy val controllers_StreamController_stream2_route = Route("GET", PathPattern(List(StaticPart(Routes.prefix),StaticPart(Routes.defaultPrefix),StaticPart("stream"))))
private[this] lazy val controllers_StreamController_stream2_invoker = createInvoker(
controllers.StreamController.stream,
HandlerDef(this.getClass.getClassLoader, "", "controllers.StreamController", "stream", Nil,"GET", """""", Routes.prefix + """stream"""))
        

// @LINE:8
private[this] lazy val controllers_Assets_versioned3_route = Route("GET", PathPattern(List(StaticPart(Routes.prefix),StaticPart(Routes.defaultPrefix),StaticPart("assets/"),DynamicPart("file", """.+""",false))))
private[this] lazy val controllers_Assets_versioned3_invoker = createInvoker(
controllers.Assets.versioned(fakeValue[String], fakeValue[Asset]),
HandlerDef(this.getClass.getClassLoader, "", "controllers.Assets", "versioned", Seq(classOf[String], classOf[Asset]),"GET", """""", Routes.prefix + """assets/$file<.+>"""))
        

// @LINE:9
private[this] lazy val controllers_BadController_bad4_route = Route("GET", PathPattern(List(StaticPart(Routes.prefix),StaticPart(Routes.defaultPrefix),StaticPart("bad"))))
private[this] lazy val controllers_BadController_bad4_invoker = createInvoker(
controllers.BadController.bad,
HandlerDef(this.getClass.getClassLoader, "", "controllers.BadController", "bad", Nil,"GET", """""", Routes.prefix + """bad"""))
        
def documentation = List(("""GET""", prefix,"""controllers.HomeController.index"""),("""GET""", prefix + (if(prefix.endsWith("/")) "" else "/") + """message""","""controllers.AsyncController.message"""),("""GET""", prefix + (if(prefix.endsWith("/")) "" else "/") + """stream""","""controllers.StreamController.stream"""),("""GET""", prefix + (if(prefix.endsWith("/")) "" else "/") + """assets/$file<.+>""","""controllers.Assets.versioned(path:String = "/public", file:Asset)"""),("""GET""", prefix + (if(prefix.endsWith("/")) "" else "/") + """bad""","""controllers.BadController.bad""")).foldLeft(List.empty[(String,String,String)]) { (s,e) => e.asInstanceOf[Any] match {
  case r @ (_,_,_) => s :+ r.asInstanceOf[(String,String,String)]
  case l => s ++ l.asInstanceOf[List[(String,String,String)]]
}}
      

def routes:PartialFunction[RequestHeader,Handler] = {

// @LINE:5
case controllers_HomeController_index0_route(params) => {
   call { 
        controllers_HomeController_index0_invoker.call(controllers.HomeController.index)
   }
}
        

// @LINE:6
case controllers_AsyncController_message1_route(params) => {
   call { 
        controllers_AsyncController_message1_invoker.call(controllers.AsyncController.message)
   }
}
        

// @LINE:7
case controllers_StreamController_stream2_route(params) => {
   call { 
        controllers_StreamController_stream2_invoker.call(controllers.StreamController.stream)
   }
}
        

// @LINE:8
case controllers_Assets_versioned3_route(params) => {
   call(Param[String]("path", Right("/public")), params.fromPath[Asset]("file", None)) { (path, file) =>
        controllers_Assets_versioned3_invoker.call(controllers.Assets.versioned(path, file))
   }
}
        

// @LINE:9
case controllers_BadController_bad4_route(params) => {
   call { 
        controllers_BadController_bad4_invoker.call(controllers.BadController.bad)
   }
}
        
}

}
     