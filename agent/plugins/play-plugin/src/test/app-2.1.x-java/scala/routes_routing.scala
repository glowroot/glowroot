// @SOURCE:D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/conf/routes
// @HASH:bf7129340517c7ffddb4d1f70c86ba09e64cd48e
// @DATE:Sat Apr 09 17:21:00 PDT 2016


import play.core._
import play.core.Router._
import play.core.j._

import play.api.mvc._
import play.libs.F

import Router.queryString

object Routes extends Router.Routes {

private var _prefix = "/"

def setPrefix(prefix: String) {
  _prefix = prefix  
  List[(String,Routes)]().foreach {
    case (p, router) => router.setPrefix(prefix + (if(prefix.endsWith("/")) "" else "/") + p)
  }
}

def prefix = _prefix

lazy val defaultPrefix = { if(Routes.prefix.endsWith("/")) "" else "/" } 


// @LINE:5
private[this] lazy val controllers_HomeController_index0 = Route("GET", PathPattern(List(StaticPart(Routes.prefix))))
        

// @LINE:6
private[this] lazy val controllers_AsyncController_message1 = Route("GET", PathPattern(List(StaticPart(Routes.prefix),StaticPart(Routes.defaultPrefix),StaticPart("message"))))
        

// @LINE:7
private[this] lazy val controllers_StreamController_stream2 = Route("GET", PathPattern(List(StaticPart(Routes.prefix),StaticPart(Routes.defaultPrefix),StaticPart("stream"))))
        

// @LINE:8
private[this] lazy val controllers_Assets_at3 = Route("GET", PathPattern(List(StaticPart(Routes.prefix),StaticPart(Routes.defaultPrefix),StaticPart("assets/"),DynamicPart("file", """.+""",false))))
        

// @LINE:9
private[this] lazy val controllers_BadController_bad4 = Route("GET", PathPattern(List(StaticPart(Routes.prefix),StaticPart(Routes.defaultPrefix),StaticPart("bad"))))
        
def documentation = List(("""GET""", prefix,"""controllers.HomeController.index"""),("""GET""", prefix + (if(prefix.endsWith("/")) "" else "/") + """message""","""controllers.AsyncController.message"""),("""GET""", prefix + (if(prefix.endsWith("/")) "" else "/") + """stream""","""controllers.StreamController.stream"""),("""GET""", prefix + (if(prefix.endsWith("/")) "" else "/") + """assets/$file<.+>""","""controllers.Assets.at(path:String = "/public", file:String)"""),("""GET""", prefix + (if(prefix.endsWith("/")) "" else "/") + """bad""","""controllers.BadController.bad""")).foldLeft(List.empty[(String,String,String)]) { (s,e) => e.asInstanceOf[Any] match {
  case r @ (_,_,_) => s :+ r.asInstanceOf[(String,String,String)]
  case l => s ++ l.asInstanceOf[List[(String,String,String)]] 
}}
       
    
def routes:PartialFunction[RequestHeader,Handler] = {        

// @LINE:5
case controllers_HomeController_index0(params) => {
   call { 
        invokeHandler(controllers.HomeController.index, HandlerDef(this, "controllers.HomeController", "index", Nil,"GET", """""", Routes.prefix + """"""))
   }
}
        

// @LINE:6
case controllers_AsyncController_message1(params) => {
   call { 
        invokeHandler(controllers.AsyncController.message, HandlerDef(this, "controllers.AsyncController", "message", Nil,"GET", """""", Routes.prefix + """message"""))
   }
}
        

// @LINE:7
case controllers_StreamController_stream2(params) => {
   call { 
        invokeHandler(controllers.StreamController.stream, HandlerDef(this, "controllers.StreamController", "stream", Nil,"GET", """""", Routes.prefix + """stream"""))
   }
}
        

// @LINE:8
case controllers_Assets_at3(params) => {
   call(Param[String]("path", Right("/public")), params.fromPath[String]("file", None)) { (path, file) =>
        invokeHandler(controllers.Assets.at(path, file), HandlerDef(this, "controllers.Assets", "at", Seq(classOf[String], classOf[String]),"GET", """""", Routes.prefix + """assets/$file<.+>"""))
   }
}
        

// @LINE:9
case controllers_BadController_bad4(params) => {
   call { 
        invokeHandler(controllers.BadController.bad, HandlerDef(this, "controllers.BadController", "bad", Nil,"GET", """""", Routes.prefix + """bad"""))
   }
}
        
}
    
}
        