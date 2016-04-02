
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
object index extends BaseScalaTemplate[play.api.templates.HtmlFormat.Appendable,Format[play.api.templates.HtmlFormat.Appendable]](play.api.templates.HtmlFormat) with play.api.templates.Template1[String,play.api.templates.HtmlFormat.Appendable] {

    /*
 * This template takes a single argument, a String containing a
 * message to display.
 */
    def apply/*5.2*/(message: String):play.api.templates.HtmlFormat.Appendable = {
        _display_ {

Seq[Any](format.raw/*5.19*/("""

"""),format.raw/*11.4*/("""
"""),_display_(Seq[Any](/*12.2*/main("Welcome to Play")/*12.25*/ {_display_(Seq[Any](format.raw/*12.27*/("""

    """),format.raw/*17.8*/("""
    """),_display_(Seq[Any](/*18.6*/play20/*18.12*/.welcome(message, style = "Scala"))),format.raw/*18.46*/("""

""")))})),format.raw/*20.2*/("""
"""))}
    }
    
    def render(message:String): play.api.templates.HtmlFormat.Appendable = apply(message)
    
    def f:((String) => play.api.templates.HtmlFormat.Appendable) = (message) => apply(message)
    
    def ref: this.type = this

}
                /*
                    -- GENERATED --
                    DATE: Sat Apr 09 16:55:08 PDT 2016
                    SOURCE: D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/app/views/index.scala.html
                    HASH: 89383ff254192989b105eefc89eae2a87a31ab46
                    MATRIX: 952->95|1063->112|1092->312|1129->314|1161->337|1201->339|1234->468|1275->474|1290->480|1346->514|1380->517
                    LINES: 32->5|35->5|37->11|38->12|38->12|38->12|40->17|41->18|41->18|41->18|43->20
                    -- GENERATED --
                */
            