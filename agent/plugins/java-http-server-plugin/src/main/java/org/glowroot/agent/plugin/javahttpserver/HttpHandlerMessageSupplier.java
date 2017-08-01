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

import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;

class HttpHandlerMessageSupplier extends MessageSupplier {

    private final String requestMethod;
    private final String requestUri;
    private final @Nullable String requestQueryString;

    private final ImmutableMap<String, Object> requestHeaders;
    private @Nullable ImmutableMap<String, Object> responseHeaders;

    private final @Nullable String requestRemoteAddr;
    private final @Nullable String requestRemoteHost;

    HttpHandlerMessageSupplier(String requestMethod, String requestUri,
            @Nullable String requestQueryString, ImmutableMap<String, Object> requestHeaders,
            @Nullable String requestRemoteAddr, @Nullable String requestRemoteHost) {
        this.requestMethod = requestMethod;
        this.requestUri = requestUri;
        this.requestQueryString = requestQueryString;
        this.requestHeaders = requestHeaders;
        this.requestRemoteAddr = requestRemoteAddr;
        this.requestRemoteHost = requestRemoteHost;
    }

    @Override
    public Message get() {
        Map<String, Object> detail = Maps.newHashMap();
        detail.put("Request http method", requestMethod);
        if (requestQueryString != null) {
            // including empty query string since that means request ended with ?
            detail.put("Request query string", requestQueryString);
        }
        if (!requestHeaders.isEmpty()) {
            detail.put("Request headers", requestHeaders);
        }
        if (requestRemoteAddr != null) {
            detail.put("Request remote address", requestRemoteAddr);
        }
        if (requestRemoteHost != null) {
            detail.put("Request remote host", requestRemoteHost);
        }
        if (responseHeaders != null && !responseHeaders.isEmpty()) {
            detail.put("Response headers", responseHeaders);
        }
        return Message.create(requestUri, detail);
    }

    void setResponseHeaders(ImmutableMap<String, Object> responseHeaders) {
        this.responseHeaders = responseHeaders;
    }
}
