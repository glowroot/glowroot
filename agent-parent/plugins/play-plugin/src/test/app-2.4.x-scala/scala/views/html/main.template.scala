
package views.html

import play.twirl.api._
import play.twirl.api.TemplateMagic._


     object main_Scope0 {
import models._
import controllers._
import play.api.i18n._
import views.html._
import play.api.templates.PlayMagic._
import play.api.mvc._
import play.api.data._

class main extends BaseScalaTemplate[play.twirl.api.HtmlFormat.Appendable,Format[play.twirl.api.HtmlFormat.Appendable]](play.twirl.api.HtmlFormat) with play.twirl.api.Template2[String,Html,play.twirl.api.HtmlFormat.Appendable] {

  /*
 * This template is called from the `index` template. This template
 * handles the rendering of the page header and body tags. It takes
 * two arguments, a `String` for the title of the page and an `Html`
 * object to insert into the body of the page.
 */
  def apply/*7.2*/(title: String)(content: Html):play.twirl.api.HtmlFormat.Appendable = {
    _display_ {
      {


Seq[Any](format.raw/*7.32*/("""

"""),format.raw/*9.1*/("""<!DOCTYPE html>
<html lang="en">
    <head>
        """),format.raw/*12.62*/("""
        """),format.raw/*13.9*/("""<title>"""),_display_(/*13.17*/title),format.raw/*13.22*/("""</title>
        <script src=""""),_display_(/*14.23*/routes/*14.29*/.Assets.versioned("scripts/empty.js")),format.raw/*14.66*/("""" type="text/javascript"></script>
    </head>
    <body>
        """),format.raw/*18.32*/("""
        """),_display_(/*19.10*/content),format.raw/*19.17*/("""
    """),format.raw/*20.5*/("""</body>
</html>
"""))
      }
    }
  }

  def render(title:String,content:Html): play.twirl.api.HtmlFormat.Appendable = apply(title)(content)

  def f:((String) => (Html) => play.twirl.api.HtmlFormat.Appendable) = (title) => (content) => apply(title)(content)

  def ref: this.type = this

}


}

/*
 * This template is called from the `index` template. This template
 * handles the rendering of the page header and body tags. It takes
 * two arguments, a `String` for the title of the page and an `Html`
 * object to insert into the body of the page.
 */
object main extends main_Scope0.main
              /*
                  -- GENERATED --
                  DATE: Sat Apr 09 15:57:53 PDT 2016
                  SOURCE: D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/app/views/main.scala.html
                  HASH: 4890290053f1214af9bcb05a8c7fb9d9aaf3458d
                  MATRIX: 784->260|909->290|937->292|1017->397|1053->406|1088->414|1114->419|1172->450|1187->456|1245->493|1339->649|1376->659|1404->666|1436->671
                  LINES: 25->7|30->7|32->9|35->12|36->13|36->13|36->13|37->14|37->14|37->14|40->18|41->19|41->19|42->20
                  -- GENERATED --
              */
          