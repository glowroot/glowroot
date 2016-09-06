
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
                    DATE: Sat Apr 09 16:50:36 PDT 2016
                    SOURCE: D:/git/trask/glowroot/agent-parent/plugins/play-plugin/tmp-router-files/app/views/main.scala.html
                    HASH: a4249dda8f4a0c64209e9e7c8302d2a1724da9f5
                    MATRIX: 1068->260|1192->290|1274->397|1327->414|1354->419|1421->450|1436->456|1488->486|1582->642|1628->652|1657->659
                    LINES: 29->7|32->7|37->12|38->13|38->13|39->14|39->14|39->14|42->18|43->19|43->19
                    -- GENERATED --
                */
            