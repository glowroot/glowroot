/*
 * Copyright 2013 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.IOException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class IndexHtmlService {

    private final String baseHref;
    private final HttpSessionManager httpSessionManager;
    private final LayoutJsonService layoutJsonService;

    IndexHtmlService(String baseHref, HttpSessionManager httpSessionManager,
            LayoutJsonService layoutJsonService) {
        this.baseHref = baseHref;
        this.httpSessionManager = httpSessionManager;
        this.layoutJsonService = layoutJsonService;
    }

    HttpResponse handleRequest(HttpRequest request) throws IOException {
        URL url = Resources.getResource("org/glowroot/local/ui/app-dist/index.html");
        String indexHtml = Resources.toString(url, Charsets.UTF_8);
        Pattern scriptPattern = Pattern.compile("<script></script>");
        Matcher scriptMatcher = scriptPattern.matcher(indexHtml);
        String layout;
        if (httpSessionManager.needsAuthentication(request)) {
            layout = layoutJsonService.getUnauthenticatedLayout();
        } else {
            layout = layoutJsonService.getLayout();
        }
        indexHtml = scriptMatcher.replaceFirst("<script>var layout=" + layout + ";</script>");
        if (!baseHref.equals("/")) {
            Pattern baseHrefPattern = Pattern.compile("<base href=\"/\">");
            Matcher baseHrefMatcher = baseHrefPattern.matcher(indexHtml);
            indexHtml = baseHrefMatcher.replaceFirst("<base href=\"" + baseHref + "\">");
        }
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        HttpServices.preventCaching(response);
        response.headers().set(Names.CONTENT_TYPE, "text/html; charset=UTF-8");
        response.headers().set(Names.CONTENT_LENGTH, indexHtml.length());
        // X-UA-Compatible must be set via header (as opposed to via meta tag)
        // see https://github.com/h5bp/html5-boilerplate/blob/master/doc/html.md#x-ua-compatible
        response.headers().set("X-UA-Compatible", "IE=edge");
        response.setContent(ChannelBuffers.copiedBuffer(indexHtml, Charsets.UTF_8));
        return response;
    }
}
