
package views.html

import play.templates._
import play.templates.TemplateMagic._

import play.api.templates._
import play.api.templates.PlayMagic._
import models._
import controllers._
import play.api.i18n._
import play.api.mvc._
import play.api.data._
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
                    DATE: Sat Apr 09 16:50:36 PDT 2016
                    SOURCE: D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/app/views/index.scala.html
                    HASH: 5ab829a9da6246199d7a5d7e2eb4d198465a6a1f
                    MATRIX: 734->95|845->112|874->312|911->314|943->337|983->339|1016->468|1057->474|1072->480|1128->514|1162->517
                    LINES: 25->5|28->5|30->11|31->12|31->12|31->12|33->17|34->18|34->18|34->18|36->20
                    -- GENERATED --
                */
            