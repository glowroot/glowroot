
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
import play.core.j.PlayMagicForJava._
import play.mvc._
import play.data._
import play.api.data.Field
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
    
    def render(message:String): play.api.templates.Html = apply(message)
    
    def f:((String) => play.api.templates.Html) = (message) => apply(message)
    
    def ref: this.type = this

}
                /*
                    -- GENERATED --
                    DATE: Sat Apr 09 17:21:00 PDT 2016
                    SOURCE: D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/app/views/index.scala.html
                    HASH: 60f16b34e2f1f2e8f135edee72bd61225134e392
                    MATRIX: 901->95|995->112|1024->312|1061->314|1093->337|1133->339|1166->468|1207->474|1222->480|1278->514|1312->517
                    LINES: 32->5|35->5|37->11|38->12|38->12|38->12|40->17|41->18|41->18|41->18|43->20
                    -- GENERATED --
                */
            