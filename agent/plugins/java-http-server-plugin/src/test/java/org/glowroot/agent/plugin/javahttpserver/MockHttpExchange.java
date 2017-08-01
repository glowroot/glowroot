/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.agent.plugin.javahttpserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpPrincipal;

@SuppressWarnings("restriction")
public class MockHttpExchange extends com.sun.net.httpserver.HttpExchange {

    private String requestURI;
    private String method;

    private final PatchedHeaders requestHeaders;
    private final PatchedHeaders responseHeaders;
    private String queryString = "";
    private HttpPrincipal principal;

    public MockHttpExchange(String method, String requestURI) {
        this.method = method;
        this.requestURI = requestURI;
        this.requestHeaders = new PatchedHeaders();
        this.responseHeaders = new PatchedHeaders();
    }

    public MockHttpExchange() {
        this("", "");
    }

    @Override
    public void close() {}

    @Override
    public Object getAttribute(String arg0) {
        return null;
    }

    @Override
    public HttpContext getHttpContext() {
        return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return null;
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getProtocol() {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return null;
    }

    @Override
    public InputStream getRequestBody() {
        return null;
    }

    @Override
    public PatchedHeaders getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public String getRequestMethod() {
        return method;
    }

    @Override
    public URI getRequestURI() {
        String str = "http://localhost:80" + requestURI;
        if (queryString != null) {
            str += "?" + queryString;
        }
        try {
            return new URI(str);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public OutputStream getResponseBody() {
        return null;
    }

    @Override
    public int getResponseCode() {
        return 0;
    }

    @Override
    public PatchedHeaders getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public void sendResponseHeaders(int statusCode, long contentLength) throws IOException {
        this.responseHeaders.set("Content-Length", Long.toString(contentLength));
    }

    @Override
    public void setAttribute(String arg0, Object arg1) {}

    @Override
    public void setStreams(InputStream arg0, OutputStream arg1) {}

    void setQueryString(String queryString) {
        this.queryString = queryString;
    }

    void setPrincipal(HttpPrincipal principal) {
        this.principal = principal;
    }
}
