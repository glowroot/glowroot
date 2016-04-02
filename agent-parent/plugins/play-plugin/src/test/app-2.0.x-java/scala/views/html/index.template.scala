
package views.html

import play.templates._
import play.templates.TemplateMagic._

import play.api.templates._
import play.api.templates.PlayMagic._
import models._
import controllers._
import java.lang._
import java.util._
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import play.api.i18n._
import play.api.templates.PlayMagicForJava._
import play.mvc._
import play.data._
import play.api.data.Field
import com.avaje.ebean._
import play.mvc.Http.Context.Implicit._
import views.html._
/*
 * This template takes a single argument, a String containing a
 * message to display.
 */
object index extends BaseScalaTemplate[play.api.templates.Html,Format[play.api.templates.Html]](play.api.templates.HtmlFormat) with play.api.templates.Template1[String,play.api.templates.Html] {

    /*
 * This template takes a single argument, a String containing a
 * message to display.
 */
    def apply/*5.2*/(message: String):play.api.templates.Html = {
        _display_ {

Seq[Any](format.raw/*5.19*/("""

"""),format.raw/*11.4*/("""
"""),_display_(Seq[Any](/*12.2*/main("Welcome to Play")/*12.25*/ {_display_(Seq[Any](format.raw/*12.27*/("""

    """),format.raw/*17.8*/("""
    """),_display_(Seq[Any](/*18.6*/play20/*18.12*/.welcome(message, style = "Scala"))),format.raw/*18.46*/("""

""")))})),format.raw/*20.2*/("""
"""))}
    }
    
    def render(message:String) = apply(message)
    
    def f:((String) => play.api.templates.Html) = (message) => apply(message)
    
    def ref = this

}
                /*
                    -- GENERATED --
                    DATE: Sat Apr 09 19:37:22 PDT 2016
                    SOURCE: D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/app/views/index.scala.html
                    HASH: 60f16b34e2f1f2e8f135edee72bd61225134e392
                    MATRIX: 933->95|1027->112|1056->312|1093->314|1125->337|1165->339|1198->468|1239->474|1254->480|1310->514|1344->517
                    LINES: 33->5|36->5|38->11|39->12|39->12|39->12|41->17|42->18|42->18|42->18|44->20
                    -- GENERATED --
                */
            