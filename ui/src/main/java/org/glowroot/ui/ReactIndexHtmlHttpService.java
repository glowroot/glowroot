/*
 * Copyright 2025 the original author or authors.
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

import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.ui.CommonHandler.CommonRequest;
import org.glowroot.ui.CommonHandler.CommonResponse;
import org.glowroot.ui.HttpSessionManager.Authentication;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

class ReactIndexHtmlHttpService implements HttpService {

    private final LayoutService layoutService;

    ReactIndexHtmlHttpService(LayoutService layoutService) {
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
        URL url = ReactIndexHtmlHttpService.class
                .getResource("/org/glowroot/ui/react-dist/index.html");
        if (url == null) {
            // react UI not built (e.g. glowroot.ui.skip=true), redirect to classic UI
            String contextPath = request.getContextPath();
            String baseHref = contextPath.equals("/") ? "/" : contextPath + "/";
            CommonResponse response = new CommonResponse(OK, MediaType.HTML_UTF_8,
                    "<html><body>Modern UI not available."
                            + " <a href=\"" + baseHref + "\">Go to classic UI</a>"
                            + "</body></html>");
            return response;
        }
        String indexHtml = Resources.toString(checkNotNull(url), UTF_8);
        String layout = layoutService.getLayoutJson(authentication);
        String contextPath = request.getContextPath();
        String baseHref =
                contextPath.equals("/") ? "/modern/" : contextPath + "/modern/";
        String faviconHref =
                (contextPath.equals("/") ? "" : contextPath) + "/favicon.ico";
        indexHtml = indexHtml.replace("<base href=\"/modern/\">",
                "<base href=\"" + baseHref + "\"><script>var layout=" + layout
                        + ";var contextPath='" + contextPath + "'</script>")
                .replace("href=\"/favicon.ico\"", "href=\"" + faviconHref + "\"")
                .replace("\"/modern/assets/", "\"" + baseHref + "assets/");
        CommonResponse response = new CommonResponse(OK, MediaType.HTML_UTF_8, indexHtml);
        return response;
    }
}
