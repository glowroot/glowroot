
package views.html

import play.twirl.api._
import play.twirl.api.TemplateMagic._

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
object index extends BaseScalaTemplate[play.twirl.api.HtmlFormat.Appendable,Format[play.twirl.api.HtmlFormat.Appendable]](play.twirl.api.HtmlFormat) with play.twirl.api.Template1[String,play.twirl.api.HtmlFormat.Appendable] {

  /*
 * This template takes a single argument, a String containing a
 * message to display.
 */
  def apply/*5.2*/(message: String):play.twirl.api.HtmlFormat.Appendable = {
      _display_ {

Seq[Any](format.raw/*5.19*/("""

"""),format.raw/*11.4*/("""
"""),_display_(/*12.2*/main("Welcome to Play")/*12.25*/ {_display_(Seq[Any](format.raw/*12.27*/("""

    """),format.raw/*17.8*/("""
    """),_display_(/*18.6*/play20/*18.12*/.welcome(message, style = "Scala")),format.raw/*18.46*/("""

""")))}),format.raw/*20.2*/("""
"""))}
  }

  def render(message:String): play.twirl.api.HtmlFormat.Appendable = apply(message)

  def f:((String) => play.twirl.api.HtmlFormat.Appendable) = (message) => apply(message)

  def ref: this.type = this

}
              /*
                  -- GENERATED --
                  DATE: Sat Apr 09 16:08:52 PDT 2016
                  SOURCE: D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/app/views/index.scala.html
                  HASH: f142d4ede426721b8fa5ef54afe0697b08575721
                  MATRIX: 683->95|788->112|817->312|845->314|877->337|917->339|950->468|982->474|997->480|1052->514|1085->517
                  LINES: 25->5|28->5|30->11|31->12|31->12|31->12|33->17|34->18|34->18|34->18|36->20
                  -- GENERATED --
              */
          