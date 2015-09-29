/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.server.ui;

import java.net.URL;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.io.Resources;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class IndexHtmlHttpService implements UnauthenticatedHttpService {

    private static final String BASE_HREF;

    private static final @Nullable String GOOGLE_ANALYTICS_TRACKING_ID =
            System.getProperty("glowroot.internal.googleAnalyticsTrackingId");

    static {
        String uiBase = System.getProperty("glowroot.ui.base");
        if (Strings.isNullOrEmpty(uiBase)) {
            BASE_HREF = "/";
        } else {
            BASE_HREF = uiBase;
        }
    }

    private final HttpSessionManager httpSessionManager;
    private final LayoutService layoutJsonService;

    IndexHtmlHttpService(HttpSessionManager httpSessionManager, LayoutService layoutJsonService) {
        this.httpSessionManager = httpSessionManager;
        this.layoutJsonService = layoutJsonService;
    }

    @Override
    public FullHttpResponse handleRequest(ChannelHandlerContext ctx, HttpRequest request)
            throws Exception {
        URL url = Resources.getResource("org/glowroot/local/ui/app-dist/index.html");
        String indexHtml = Resources.toString(url, Charsets.UTF_8);
        String layout;
        if (httpSessionManager.hasReadAccess(request)) {
            layout = layoutJsonService.getLayout();
        } else {
            layout = layoutJsonService.getNeedsAuthenticationLayout();
        }
        String authenticatedUser = httpSessionManager.getAuthenticatedUser(request);
        String layoutScript = "var layout=" + layout + ";var authenticatedUser = '"
                + Strings.nullToEmpty(authenticatedUser) + "'";
        indexHtml = indexHtml.replaceFirst("<base href=\"/\">",
                "<base href=\"" + BASE_HREF + "\"><script>" + layoutScript + "</script>");
        // this is to work around an issue with IE10-11 (IE9 is OK)
        // (even without reverse proxy/non-root base href)
        // IE doesn't use the base href when loading the favicon
        indexHtml = indexHtml.replaceFirst(
                "<link rel=\"shortcut icon\" href=\"favicon\\.([0-9a-f]+)\\.ico\">",
                "<script>document.write('<link rel=\"shortcut icon\" href=\"'"
                        + " + document.getElementsByTagName(\"base\")[0].href"
                        + " + 'favicon.$1.ico\">');</script>");
        if (GOOGLE_ANALYTICS_TRACKING_ID != null) {
            // this is for demo.glowroot.org
            indexHtml = indexHtml.replaceFirst(
                    "<div class=\"navbar-brand\">(\\s*)Glowroot(\\s*)</div>",
                    "<a href=\"https://glowroot.org\" class=\"navbar-brand\">$1Glowroot$2</a>");
            indexHtml = indexHtml.replaceFirst("</body>",
                    "<script>(function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]"
                            + "||function(){(i[r].q=i[r].q||[]).push(arguments)},"
                            + "i[r].l=1*new Date();a=s.createElement(o),"
                            + "m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;"
                            + "m.parentNode.insertBefore(a,m)})(window,document,'script',"
                            + "'//www.google-analytics.com/analytics.js','ga');ga('create', '"
                            + GOOGLE_ANALYTICS_TRACKING_ID + "', 'auto');</script>\n</body>");
        }
        ByteBuf content = Unpooled.copiedBuffer(indexHtml, Charsets.ISO_8859_1);
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, content);
        HttpServices.preventCaching(response);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, indexHtml.length());
        // X-UA-Compatible must be set via header (as opposed to via meta tag)
        // see https://github.com/h5bp/html5-boilerplate/blob/master/doc/html.md#x-ua-compatible
        response.headers().set("X-UA-Compatible", "IE=edge");
        return response;
    }
}
