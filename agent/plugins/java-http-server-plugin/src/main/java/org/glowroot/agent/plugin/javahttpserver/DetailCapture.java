/*
 * Copyright 2017-2018 the original author or authors.
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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.util.ImmutableList;
import org.glowroot.agent.plugin.api.util.ImmutableMap;
import org.glowroot.agent.plugin.javahttpserver.HttpHandlerAspect.Headers;
import org.glowroot.agent.plugin.javahttpserver.HttpHandlerAspect.HttpExchange;

class DetailCapture {

    private DetailCapture() {}

    static Map<String, Object> captureRequestHeaders(HttpExchange exchange) {
        List<Pattern> capturePatterns = JavaHttpServerPluginProperties.captureRequestHeaders();
        if (capturePatterns.isEmpty()) {
            return Collections.emptyMap();
        }
        return captureHeaders(capturePatterns, exchange.glowroot$getRequestHeaders(),
                JavaHttpServerPluginProperties.maskRequestHeaders());
    }

    static Map<String, Object> captureResponseHeaders(HttpExchange exchange) {
        List<Pattern> capturePatterns = JavaHttpServerPluginProperties.captureResponseHeaders();
        if (capturePatterns.isEmpty()) {
            return Collections.emptyMap();
        }
        return captureHeaders(capturePatterns, exchange.glowroot$getResponseHeaders(),
                Collections.<Pattern>emptyList());
    }

    private static Map<String, Object> captureHeaders(List<Pattern> capturePatterns,
            @Nullable Headers headers, List<Pattern> maskPatterns) {
        if (headers == null) {
            return Collections.emptyMap();
        }
        Set<String> headerNames = headers.keySet();
        if (headerNames == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> headersMap = new HashMap<String, Object>();
        for (String name : headerNames) {
            if (name == null) {
                continue;
            }
            // converted to lower case for case-insensitive matching (patterns are lower case)
            String keyLowerCase = name.toLowerCase(Locale.ENGLISH);
            if (!matchesOneOf(keyLowerCase, capturePatterns)) {
                continue;
            }
            if (matchesOneOf(keyLowerCase, maskPatterns)) {
                headersMap.put(name, "****");
                continue;
            }
            List<String> values = headers.get(name);
            if (values != null) {
                captureHeader(name, values, headersMap);
            }
        }
        return ImmutableMap.copyOf(headersMap);
    }

    static @Nullable String captureRequestRemoteAddr(HttpExchange exchange) {
        if (JavaHttpServerPluginProperties.captureRequestRemoteAddr()) {
            return getRemoteAddr(exchange.getRemoteAddress());
        } else {
            return null;
        }
    }

    private static @Nullable String getRemoteAddr(@Nullable InetSocketAddress remoteAddress) {
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        } else {
            return null;
        }
    }

    static @Nullable String captureRequestRemoteHost(HttpExchange exchange) {
        if (JavaHttpServerPluginProperties.captureRequestRemoteHost()) {
            return getRemoteHost(exchange.getRemoteAddress());
        } else {
            return null;
        }
    }

    private static @Nullable String getRemoteHost(@Nullable InetSocketAddress remoteAddress) {
        if (remoteAddress != null) {
            return remoteAddress.getHostName();
        } else {
            return null;
        }
    }

    private static boolean matchesOneOf(String key, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }

    private static void captureHeader(String name, List<String> values,
            Map<String, Object> header) {
        if (values.isEmpty()) {
            header.put(name, "");
        } else {
            List<String> list = new ArrayList<String>();
            for (String value : values) {
                list.add(Strings.nullToEmpty(value));
            }
            header.put(name, ImmutableList.copyOf(list));
        }
    }
}
