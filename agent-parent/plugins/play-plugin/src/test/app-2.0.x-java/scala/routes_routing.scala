// @SOURCE:D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/conf/routes
// @HASH:bf7129340517c7ffddb4d1f70c86ba09e64cd48e
// @DATE:Sat Apr 09 19:37:22 PDT 2016

import play.core._
import play.core.Router._
import play.core.j._

import play.api.mvc._
import play.libs.F

import Router.queryString

object Routes extends Router.Routes {


// @LINE:5
val controllers_HomeController_index0 = Route("GET", PathPattern(List(StaticPart("/"))))
                    

// @LINE:6
val controllers_AsyncController_message1 = Route("GET", PathPattern(List(StaticPart("/message"))))
                    

// @LINE:7
val controllers_StreamController_stream2 = Route("GET", PathPattern(List(StaticPart("/stream"))))
                    

// @LINE:8
val controllers_Assets_at3 = Route("GET", PathPattern(List(StaticPart("/assets/"),DynamicPart("file", """.+"""))))
                    

// @LINE:9
val controllers_BadController_bad4 = Route("GET", PathPattern(List(StaticPart("/bad"))))
                    
def documentation = List(("""GET""","""/""","""controllers.HomeController.index"""),("""GET""","""/message""","""controllers.AsyncController.message"""),("""GET""","""/stream""","""controllers.StreamController.stream"""),("""GET""","""/assets/$file<.+>""","""controllers.Assets.at(path:String = "/public", file:String)"""),("""GET""","""/bad""","""controllers.BadController.bad"""))
             
    
def routes:PartialFunction[RequestHeader,Handler] = {        

// @LINE:5
case controllers_HomeController_index0(params) => {
   call { 
        invokeHandler(_root_.controllers.HomeController.index, HandlerDef(this, "controllers.HomeController", "index", Nil))
   }
}
                    

// @LINE:6
case controllers_AsyncController_message1(params) => {
   call { 
        invokeHandler(_root_.controllers.AsyncController.message, HandlerDef(this, "controllers.AsyncController", "message", Nil))
   }
}
                    

// @LINE:7
case controllers_StreamController_stream2(params) => {
   call { 
        invokeHandler(_root_.controllers.StreamController.stream, HandlerDef(this, "controllers.StreamController", "stream", Nil))
   }
}
                    

// @LINE:8
case controllers_Assets_at3(params) => {
   call(Param[String]("path", Right("/public")), params.fromPath[String]("file", None)) { (path, file) =>
        invokeHandler(_root_.controllers.Assets.at(path, file), HandlerDef(this, "controllers.Assets", "at", Seq(classOf[String], classOf[String])))
   }
}
                    

// @LINE:9
case controllers_BadController_bad4(params) => {
   call { 
        invokeHandler(_root_.controllers.BadController.bad, HandlerDef(this, "controllers.BadController", "bad", Nil))
   }
}
                    
}
    
}
                