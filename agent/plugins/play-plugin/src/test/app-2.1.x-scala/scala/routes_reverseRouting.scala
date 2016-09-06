// @SOURCE:D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/conf/routes
// @HASH:bf7129340517c7ffddb4d1f70c86ba09e64cd48e
// @DATE:Sat Apr 09 17:19:53 PDT 2016

import Routes.{prefix => _prefix, defaultPrefix => _defaultPrefix}
import play.core._
import play.core.Router._
import play.core.j._

import play.api.mvc._


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
def at(file:String): Call = {
   Call("GET", _prefix + { _defaultPrefix } + "assets/" + implicitly[PathBindable[String]].unbind("file", file))
}
                                                
    
}
                          

// @LINE:9
class ReverseBadController {
    

// @LINE:9
def bad(): Call = {
   Call("GET", _prefix + { _defaultPrefix } + "bad")
}
                                                
    
}
                          

// @LINE:5
class ReverseHomeController {
    

// @LINE:5
def index(): Call = {
   Call("GET", _prefix)
}
                                                
    
}
                          

// @LINE:6
class ReverseAsyncController {
    

// @LINE:6
def message(): Call = {
   Call("GET", _prefix + { _defaultPrefix } + "message")
}
                                                
    
}
                          

// @LINE:7
class ReverseStreamController {
    

// @LINE:7
def stream(): Call = {
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

// @LINE:8
class ReverseAssets {
    

// @LINE:8
def at : JavascriptReverseRoute = JavascriptReverseRoute(
   "controllers.Assets.at",
   """
      function(file) {
      return _wA({method:"GET", url:"""" + _prefix + { _defaultPrefix } + """" + "assets/" + (""" + implicitly[PathBindable[String]].javascriptUnbind + """)("file", file)})
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
def at(path:String, file:String): play.api.mvc.HandlerRef[_] = new play.api.mvc.HandlerRef(
   controllers.Assets.at(path, file), HandlerDef(this, "controllers.Assets", "at", Seq(classOf[String], classOf[String]), "GET", """""", _prefix + """assets/$file<.+>""")
)
                      
    
}
                          

// @LINE:9
class ReverseBadController {
    

// @LINE:9
def bad(): play.api.mvc.HandlerRef[_] = new play.api.mvc.HandlerRef(
   controllers.BadController.bad(), HandlerDef(this, "controllers.BadController", "bad", Seq(), "GET", """""", _prefix + """bad""")
)
                      
    
}
                          

// @LINE:5
class ReverseHomeController {
    

// @LINE:5
def index(): play.api.mvc.HandlerRef[_] = new play.api.mvc.HandlerRef(
   controllers.HomeController.index(), HandlerDef(this, "controllers.HomeController", "index", Seq(), "GET", """""", _prefix + """""")
)
                      
    
}
                          

// @LINE:6
class ReverseAsyncController {
    

// @LINE:6
def message(): play.api.mvc.HandlerRef[_] = new play.api.mvc.HandlerRef(
   controllers.AsyncController.message(), HandlerDef(this, "controllers.AsyncController", "message", Seq(), "GET", """""", _prefix + """message""")
)
                      
    
}
                          

// @LINE:7
class ReverseStreamController {
    

// @LINE:7
def stream(): play.api.mvc.HandlerRef[_] = new play.api.mvc.HandlerRef(
   controllers.StreamController.stream(), HandlerDef(this, "controllers.StreamController", "stream", Seq(), "GET", """""", _prefix + """stream""")
)
                      
    
}
                          
}
                  
      