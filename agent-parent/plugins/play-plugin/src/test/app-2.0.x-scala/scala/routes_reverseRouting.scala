// @SOURCE:D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/conf/routes
// @HASH:bf7129340517c7ffddb4d1f70c86ba09e64cd48e
// @DATE:Sat Apr 09 19:36:53 PDT 2016

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
def at(file:String) = {
   Call("GET", "/assets/" + implicitly[PathBindable[String]].unbind("file", file))
}
                                                        

                      
    
}
                            

// @LINE:9
class ReverseBadController {
    


 
// @LINE:9
def bad() = {
   Call("GET", "/bad")
}
                                                        

                      
    
}
                            

// @LINE:5
class ReverseHomeController {
    


 
// @LINE:5
def index() = {
   Call("GET", "/")
}
                                                        

                      
    
}
                            

// @LINE:6
class ReverseAsyncController {
    


 
// @LINE:6
def message() = {
   Call("GET", "/message")
}
                                                        

                      
    
}
                            

// @LINE:7
class ReverseStreamController {
    


 
// @LINE:7
def stream() = {
   Call("GET", "/stream")
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
def at = JavascriptReverseRoute(
   "controllers.Assets.at",
   """
      function(file) {
      return _wA({method:"GET", url:"/assets/" + (""" + implicitly[PathBindable[String]].javascriptUnbind + """)("file", file)})
      }
   """
)
                                                        

                      
    
}
                            

// @LINE:9
class ReverseBadController {
    


 
// @LINE:9
def bad = JavascriptReverseRoute(
   "controllers.BadController.bad",
   """
      function() {
      return _wA({method:"GET", url:"/bad"})
      }
   """
)
                                                        

                      
    
}
                            

// @LINE:5
class ReverseHomeController {
    


 
// @LINE:5
def index = JavascriptReverseRoute(
   "controllers.HomeController.index",
   """
      function() {
      return _wA({method:"GET", url:"/"})
      }
   """
)
                                                        

                      
    
}
                            

// @LINE:6
class ReverseAsyncController {
    


 
// @LINE:6
def message = JavascriptReverseRoute(
   "controllers.AsyncController.message",
   """
      function() {
      return _wA({method:"GET", url:"/message"})
      }
   """
)
                                                        

                      
    
}
                            

// @LINE:7
class ReverseStreamController {
    


 
// @LINE:7
def stream = JavascriptReverseRoute(
   "controllers.StreamController.stream",
   """
      function() {
      return _wA({method:"GET", url:"/stream"})
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
def at(path:String, file:String) = new play.api.mvc.HandlerRef(
   controllers.Assets.at(path, file), HandlerDef(this, "controllers.Assets", "at", Seq(classOf[String], classOf[String]))
)
                              

                      
    
}
                            

// @LINE:9
class ReverseBadController {
    


 
// @LINE:9
def bad() = new play.api.mvc.HandlerRef(
   controllers.BadController.bad(), HandlerDef(this, "controllers.BadController", "bad", Seq())
)
                              

                      
    
}
                            

// @LINE:5
class ReverseHomeController {
    


 
// @LINE:5
def index() = new play.api.mvc.HandlerRef(
   controllers.HomeController.index(), HandlerDef(this, "controllers.HomeController", "index", Seq())
)
                              

                      
    
}
                            

// @LINE:6
class ReverseAsyncController {
    


 
// @LINE:6
def message() = new play.api.mvc.HandlerRef(
   controllers.AsyncController.message(), HandlerDef(this, "controllers.AsyncController", "message", Seq())
)
                              

                      
    
}
                            

// @LINE:7
class ReverseStreamController {
    


 
// @LINE:7
def stream() = new play.api.mvc.HandlerRef(
   controllers.StreamController.stream(), HandlerDef(this, "controllers.StreamController", "stream", Seq())
)
                              

                      
    
}
                            
}
                    
                