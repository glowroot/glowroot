
package views.html

import play.twirl.api._
import play.twirl.api.TemplateMagic._

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
    """),_display_(/*18.6*/play20/*18.12*/.welcome(message, style = "Java")),format.raw/*18.45*/("""

""")))}),format.raw/*20.2*/("""
"""))}
  }

  def render(message:String): play.twirl.api.HtmlFormat.Appendable = apply(message)

  def f:((String) => play.twirl.api.HtmlFormat.Appendable) = (message) => apply(message)

  def ref: this.type = this

}
              /*
                  -- GENERATED --
                  DATE: Sat Apr 09 16:10:22 PDT 2016
                  SOURCE: D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/app/views/index.scala.html
                  HASH: 9477d581e1d6acaa7710892ef32646f1ad5553d8
                  MATRIX: 901->95|1006->112|1035->312|1063->314|1095->337|1135->339|1168->468|1200->474|1215->480|1269->513|1302->516
                  LINES: 32->5|35->5|37->11|38->12|38->12|38->12|40->17|41->18|41->18|41->18|43->20
                  -- GENERATED --
              */
          