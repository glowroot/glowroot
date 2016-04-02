// @SOURCE:D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/conf/routes
// @HASH:175a60cc39e69a6dc7aafb7404814ae7d039e113
// @DATE:Sat Apr 09 16:10:22 PDT 2016

import Routes.{prefix => _prefix, defaultPrefix => _defaultPrefix}
import play.core._
import play.core.Router._
import play.core.Router.HandlerInvokerFactory._
import play.core.j._

import play.api.mvc._
import _root_.controllers.Assets.Asset
import _root_.play.libs.F

import Router.queryString


// @LINE:9
// @LINE:8
// @LINE:7
// @LINE:6
// @LINE:5
package controllers {

// @LINE:8
class ReverseAssets {


// @LINE:8
def versioned(file:Asset): Call = {
   implicit val _rrc = new ReverseRouteContext(Map(("path", "/public")))
   Call("GET", _prefix + { _defaultPrefix } + "assets/" + implicitly[PathBindable[Asset]].unbind("file", file))
}
                        

}
                          

// @LINE:9
class ReverseBadController {


// @LINE:9
def bad(): Call = {
   import ReverseRouteContext.empty
   Call("GET", _prefix + { _defaultPrefix } + "bad")
}
                        

}
                          

// @LINE:5
class ReverseHomeController {


// @LINE:5
def index(): Call = {
   import ReverseRouteContext.empty
   Call("GET", _prefix)
}
                        

}
                          

// @LINE:6
class ReverseAsyncController {


// @LINE:6
def message(): Call = {
   import ReverseRouteContext.empty
   Call("GET", _prefix + { _defaultPrefix } + "message")
}
                        

}
                          

// @LINE:7
class ReverseStreamController {


// @LINE:7
def stream(): Call = {
   import ReverseRouteContext.empty
   Call("GET", _prefix + { _defaultPrefix } + "stream")
}
                        

}
                          
}
                  


// @LINE:9
// @LINE:8
// @LINE:7
// @LINE:6
// @LINE:5
package controllers.javascript {
import ReverseRouteContext.empty

// @LINE:8
class ReverseAssets {


// @LINE:8
def versioned : JavascriptReverseRoute = JavascriptReverseRoute(
   "controllers.Assets.versioned",
   """
      function(file) {
      return _wA({method:"GET", url:"""" + _prefix + { _defaultPrefix } + """" + "assets/" + (""" + implicitly[PathBindable[Asset]].javascriptUnbind + """)("file", file)})
      }
   """
)
                        

}
              

// @LINE:9
class ReverseBadController {


// @LINE:9
def bad : JavascriptReverseRoute = JavascriptReverseRoute(
   "controllers.BadController.bad",
   """
      function() {
      return _wA({method:"GET", url:"""" + _prefix + { _defaultPrefix } + """" + "bad"})
      }
   """
)
                        

}
              

// @LINE:5
class ReverseHomeController {


// @LINE:5
def index : JavascriptReverseRoute = JavascriptReverseRoute(
   "controllers.HomeController.index",
   """
      function() {
      return _wA({method:"GET", url:"""" + _prefix + """"})
      }
   """
)
                        

}
              

// @LINE:6
class ReverseAsyncController {


// @LINE:6
def message : JavascriptReverseRoute = JavascriptReverseRoute(
   "controllers.AsyncController.message",
   """
      function() {
      return _wA({method:"GET", url:"""" + _prefix + { _defaultPrefix } + """" + "message"})
      }
   """
)
                        

}
              

// @LINE:7
class ReverseStreamController {


// @LINE:7
def stream : JavascriptReverseRoute = JavascriptReverseRoute(
   "controllers.StreamController.stream",
   """
      function() {
      return _wA({method:"GET", url:"""" + _prefix + { _defaultPrefix } + """" + "stream"})
      }
   """
)
                        

}
              
}
        


// @LINE:9
// @LINE:8
// @LINE:7
// @LINE:6
// @LINE:5
package controllers.ref {


// @LINE:8
class ReverseAssets {


// @LINE:8
def versioned(path:String, file:Asset): play.api.mvc.HandlerRef[_] = new play.api.mvc.HandlerRef(
   controllers.Assets.versioned(path, file), HandlerDef(this.getClass.getClassLoader, "", "controllers.Assets", "versioned", Seq(classOf[String], classOf[Asset]), "GET", """""", _prefix + """assets/$file<.+>""")
)
                      

}
                          

// @LINE:9
class ReverseBadController {


// @LINE:9
def bad(): play.api.mvc.HandlerRef[_] = new play.api.mvc.HandlerRef(
   controllers.BadController.bad(), HandlerDef(this.getClass.getClassLoader, "", "controllers.BadController", "bad", Seq(), "GET", """""", _prefix + """bad""")
)
                      

}
                          

// @LINE:5
class ReverseHomeController {


// @LINE:5
def index(): play.api.mvc.HandlerRef[_] = new play.api.mvc.HandlerRef(
   controllers.HomeController.index(), HandlerDef(this.getClass.getClassLoader, "", "controllers.HomeController", "index", Seq(), "GET", """""", _prefix + """""")
)
                      

}
                          

// @LINE:6
class ReverseAsyncController {


// @LINE:6
def message(): play.api.mvc.HandlerRef[_] = new play.api.mvc.HandlerRef(
   controllers.AsyncController.message(), HandlerDef(this.getClass.getClassLoader, "", "controllers.AsyncController", "message", Seq(), "GET", """""", _prefix + """message""")
)
                      

}
                          

// @LINE:7
class ReverseStreamController {


// @LINE:7
def stream(): play.api.mvc.HandlerRef[_] = new play.api.mvc.HandlerRef(
   controllers.StreamController.stream(), HandlerDef(this.getClass.getClassLoader, "", "controllers.StreamController", "stream", Seq(), "GET", """""", _prefix + """stream""")
)
                      

}
                          
}
        
    