
// @GENERATOR:play-routes-compiler
// @SOURCE:D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/conf/routes
// @DATE:Sat Apr 09 15:57:53 PDT 2016

package router

import play.core.routing._
import play.core.routing.HandlerInvokerFactory._
import play.core.j._

import play.api.mvc._

import _root_.controllers.Assets.Asset

class Routes(
  override val errorHandler: play.api.http.HttpErrorHandler, 
  // @LINE:5
  HomeController_0: controllers.HomeController,
  // @LINE:6
  AsyncController_1: controllers.AsyncController,
  // @LINE:7
  StreamController_3: controllers.StreamController,
  // @LINE:8
  Assets_4: controllers.Assets,
  // @LINE:9
  BadController_2: controllers.BadController,
  val prefix: String
) extends GeneratedRouter {

   @javax.inject.Inject()
   def this(errorHandler: play.api.http.HttpErrorHandler,
    // @LINE:5
    HomeController_0: controllers.HomeController,
    // @LINE:6
    AsyncController_1: controllers.AsyncController,
    // @LINE:7
    StreamController_3: controllers.StreamController,
    // @LINE:8
    Assets_4: controllers.Assets,
    // @LINE:9
    BadController_2: controllers.BadController
  ) = this(errorHandler, HomeController_0, AsyncController_1, StreamController_3, Assets_4, BadController_2, "/")

  import ReverseRouteContext.empty

  def withPrefix(prefix: String): Routes = {
    router.RoutesPrefix.setPrefix(prefix)
    new Routes(errorHandler, HomeController_0, AsyncController_1, StreamController_3, Assets_4, BadController_2, prefix)
  }

  private[this] val defaultPrefix: String = {
    if (this.prefix.endsWith("/")) "" else "/"
  }

  def documentation = List(
    ("""GET""", this.prefix, """controllers.HomeController.index"""),
    ("""GET""", this.prefix + (if(this.prefix.endsWith("/")) "" else "/") + """message""", """controllers.AsyncController.message"""),
    ("""GET""", this.prefix + (if(this.prefix.endsWith("/")) "" else "/") + """stream""", """controllers.StreamController.stream"""),
    ("""GET""", this.prefix + (if(this.prefix.endsWith("/")) "" else "/") + """assets/$file<.+>""", """controllers.Assets.versioned(path:String = "/public", file:Asset)"""),
    ("""GET""", this.prefix + (if(this.prefix.endsWith("/")) "" else "/") + """bad""", """controllers.BadController.bad"""),
    Nil
  ).foldLeft(List.empty[(String,String,String)]) { (s,e) => e.asInstanceOf[Any] match {
    case r @ (_,_,_) => s :+ r.asInstanceOf[(String,String,String)]
    case l => s ++ l.asInstanceOf[List[(String,String,String)]]
  }}


  // @LINE:5
  private[this] lazy val controllers_HomeController_index0_route = Route("GET",
    PathPattern(List(StaticPart(this.prefix)))
  )
  private[this] lazy val controllers_HomeController_index0_invoker = createInvoker(
    HomeController_0.index,
    HandlerDef(this.getClass.getClassLoader,
      "router",
      "controllers.HomeController",
      "index",
      Nil,
      "GET",
      """""",
      this.prefix + """"""
    )
  )

  // @LINE:6
  private[this] lazy val controllers_AsyncController_message1_route = Route("GET",
    PathPattern(List(StaticPart(this.prefix), StaticPart(this.defaultPrefix), StaticPart("message")))
  )
  private[this] lazy val controllers_AsyncController_message1_invoker = createInvoker(
    AsyncController_1.message,
    HandlerDef(this.getClass.getClassLoader,
      "router",
      "controllers.AsyncController",
      "message",
      Nil,
      "GET",
      """""",
      this.prefix + """message"""
    )
  )

  // @LINE:7
  private[this] lazy val controllers_StreamController_stream2_route = Route("GET",
    PathPattern(List(StaticPart(this.prefix), StaticPart(this.defaultPrefix), StaticPart("stream")))
  )
  private[this] lazy val controllers_StreamController_stream2_invoker = createInvoker(
    StreamController_3.stream,
    HandlerDef(this.getClass.getClassLoader,
      "router",
      "controllers.StreamController",
      "stream",
      Nil,
      "GET",
      """""",
      this.prefix + """stream"""
    )
  )

  // @LINE:8
  private[this] lazy val controllers_Assets_versioned3_route = Route("GET",
    PathPattern(List(StaticPart(this.prefix), StaticPart(this.defaultPrefix), StaticPart("assets/"), DynamicPart("file", """.+""",false)))
  )
  private[this] lazy val controllers_Assets_versioned3_invoker = createInvoker(
    Assets_4.versioned(fakeValue[String], fakeValue[Asset]),
    HandlerDef(this.getClass.getClassLoader,
      "router",
      "controllers.Assets",
      "versioned",
      Seq(classOf[String], classOf[Asset]),
      "GET",
      """""",
      this.prefix + """assets/$file<.+>"""
    )
  )

  // @LINE:9
  private[this] lazy val controllers_BadController_bad4_route = Route("GET",
    PathPattern(List(StaticPart(this.prefix), StaticPart(this.defaultPrefix), StaticPart("bad")))
  )
  private[this] lazy val controllers_BadController_bad4_invoker = createInvoker(
    BadController_2.bad,
    HandlerDef(this.getClass.getClassLoader,
      "router",
      "controllers.BadController",
      "bad",
      Nil,
      "GET",
      """""",
      this.prefix + """bad"""
    )
  )


  def routes: PartialFunction[RequestHeader, Handler] = {
  
    // @LINE:5
    case controllers_HomeController_index0_route(params) =>
      call { 
        controllers_HomeController_index0_invoker.call(HomeController_0.index)
      }
  
    // @LINE:6
    case controllers_AsyncController_message1_route(params) =>
      call { 
        controllers_AsyncController_message1_invoker.call(AsyncController_1.message)
      }
  
    // @LINE:7
    case controllers_StreamController_stream2_route(params) =>
      call { 
        controllers_StreamController_stream2_invoker.call(StreamController_3.stream)
      }
  
    // @LINE:8
    case controllers_Assets_versioned3_route(params) =>
      call(Param[String]("path", Right("/public")), params.fromPath[Asset]("file", None)) { (path, file) =>
        controllers_Assets_versioned3_invoker.call(Assets_4.versioned(path, file))
      }
  
    // @LINE:9
    case controllers_BadController_bad4_route(params) =>
      call { 
        controllers_BadController_bad4_invoker.call(BadController_2.bad)
      }
  }
}