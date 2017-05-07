/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.ui;

import java.net.URL;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;

import org.glowroot.ui.CommonHandler.CommonRequest;
import org.glowroot.ui.CommonHandler.CommonResponse;
import org.glowroot.ui.HttpSessionManager.Authentication;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

class IndexHtmlHttpService implements HttpService {

    private static final @Nullable String GOOGLE_ANALYTICS_TRACKING_ID =
            System.getProperty("glowroot.internal.googleAnalyticsTrackingId");

    private final LayoutService layoutService;

    IndexHtmlHttpService(LayoutService layoutService) {
        this.layoutService = layoutService;
    }

    @Override
    public String getPermission() {
        // this service does not require any permission
        return "";
    }

    @Override
    public CommonResponse handleRequest(CommonRequest request, Authentication authentication)
            throws Exception {
        URL url = IndexHtmlHttpService.class.getResource("/org/glowroot/ui/app-dist/index.html");
        String indexHtml = Resources.toString(checkNotNull(url), Charsets.UTF_8);
        String layout = layoutService.getLayoutJson(authentication);
        String contextPath = request.getContextPath();
        String baseHref = contextPath.equals("/") ? "/" : contextPath + "/";
        indexHtml = indexHtml.replace("<base href=\"/\">",
                "<base href=\"" + baseHref + "\"><script>var layout=" + layout
                        + ";var contextPath='" + contextPath + "'</script>");
        // this is to work around an issue with IE10-11 (IE9 is OK)
        // (even without reverse proxy/non-root base href)
        // IE doesn't use the base href when loading the favicon
        indexHtml = indexHtml.replaceFirst(
                "<link rel=\"shortcut icon\" href=\"favicon\\.([0-9a-f]+)\\.ico\">",
                "<script>document.write('<link rel=\"shortcut icon\" href=\"'"
                        + " + document.getElementsByTagName(\"base\")[0].href"
                        + " + 'favicon.$1.ico\">')</script>");
        if (GOOGLE_ANALYTICS_TRACKING_ID != null) {
            // this is for demo.glowroot.org
            indexHtml = indexHtml.replaceFirst(
                    "<div class=\"navbar-brand\">(\\s*)Glowroot(\\s*)</div>",
                    "<a href=\"https://glowroot.org\" class=\"navbar-brand\">$1Glowroot$2</a>");
            indexHtml = indexHtml.replace("</body>",
                    "<script>(function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]"
                            + "||function(){(i[r].q=i[r].q||[]).push(arguments)},"
                            + "i[r].l=1*new Date();a=s.createElement(o),"
                            + "m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;"
                            + "m.parentNode.insertBefore(a,m)})(window,document,'script',"
                            + "'//www.google-analytics.com/analytics.js','ga');ga('create', '"
                            + GOOGLE_ANALYTICS_TRACKING_ID + "', 'auto')</script>\n</body>");
        }
        CommonResponse response = new CommonResponse(OK, MediaType.HTML_UTF_8, indexHtml);
        // X-UA-Compatible must be set via header (as opposed to via meta tag)
        // see https://github.com/h5bp/html5-boilerplate/blob/master/doc/html.md#x-ua-compatible
        response.setHeader("X-UA-Compatible", "IE=edge");
        return response;
    }
}
