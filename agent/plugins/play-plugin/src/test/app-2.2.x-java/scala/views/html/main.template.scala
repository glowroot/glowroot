
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
 * This template is called from the `index` template. This template
 * handles the rendering of the page header and body tags. It takes
 * two arguments, a `String` for the title of the page and an `Html`
 * object to insert into the body of the page.
 */
object main extends BaseScalaTemplate[play.api.templates.HtmlFormat.Appendable,Format[play.api.templates.HtmlFormat.Appendable]](play.api.templates.HtmlFormat) with play.api.templates.Template2[String,Html,play.api.templates.HtmlFormat.Appendable] {

    /*
 * This template is called from the `index` template. This template
 * handles the rendering of the page header and body tags. It takes
 * two arguments, a `String` for the title of the page and an `Html`
 * object to insert into the body of the page.
 */
    def apply/*7.2*/(title: String)(content: Html):play.api.templates.HtmlFormat.Appendable = {
        _display_ {

Seq[Any](format.raw/*7.32*/("""

<!DOCTYPE html>
<html lang="en">
    <head>
        """),format.raw/*12.62*/("""
        <title>"""),_display_(Seq[Any](/*13.17*/title)),format.raw/*13.22*/("""</title>
        <script src=""""),_display_(Seq[Any](/*14.23*/routes/*14.29*/.Assets.at("scripts/empty.js"))),format.raw/*14.59*/("""" type="text/javascript"></script>
    </head>
    <body>
        """),format.raw/*18.32*/("""
        """),_display_(Seq[Any](/*19.10*/content)),format.raw/*19.17*/("""
    </body>
</html>
"""))}
    }
    
    def render(title:String,content:Html): play.api.templates.HtmlFormat.Appendable = apply(title)(content)
    
    def f:((String) => (Html) => play.api.templates.HtmlFormat.Appendable) = (title) => (content) => apply(title)(content)
    
    def ref: this.type = this

}
                /*
                    -- GENERATED --
                    DATE: Sat Apr 09 16:55:08 PDT 2016
                    SOURCE: D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/app/views/main.scala.html
                    HASH: df93dde72f378a1d253657d3f509b5963f99d311
                    MATRIX: 1286->260|1410->290|1492->397|1545->414|1572->419|1639->450|1654->456|1706->486|1800->642|1846->652|1875->659
                    LINES: 36->7|39->7|44->12|45->13|45->13|46->14|46->14|46->14|49->18|50->19|50->19
                    -- GENERATED --
                */
            