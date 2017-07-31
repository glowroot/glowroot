/*
 * Copyright 2014-2017 the original author or authors.
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.javahttpserver.HttpHandlerAspect.Headers;
import org.glowroot.agent.plugin.javahttpserver.HttpHandlerAspect.HttpExchange;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

class DetailCapture {

    private DetailCapture() {}

    static ImmutableMap<String, Object> captureRequestHeaders(HttpExchange exchange) {
        final ImmutableList<Pattern> capturePatterns = JavaHttpServerPluginProperties.captureRequestHeaders();
        if (capturePatterns.isEmpty()) {
            return ImmutableMap.of();
        }
        return captureHeaders(capturePatterns, exchange.glowroot$getRequestHeaders());
    }

    static ImmutableMap<String, Object> captureResponseHeaders(HttpExchange exchange) {
        final ImmutableList<Pattern> capturePatterns = JavaHttpServerPluginProperties.captureResponseHeaders();
        if (capturePatterns.isEmpty()) {
            return ImmutableMap.of();
        }
        return captureHeaders(capturePatterns, exchange.glowroot$getResponseHeaders());
    }

    private static ImmutableMap<String, Object> captureHeaders(final ImmutableList<Pattern> capturePatterns,
            @Nullable Headers headers) {
        if (headers == null) {
            return ImmutableMap.of();
        }
        final Set<String> headerNames = headers.keySet();
        if (headerNames == null) {
            return ImmutableMap.of();
        }
        final ImmutableList<Pattern> maskPatterns = JavaHttpServerPluginProperties.maskRequestHeaders();
        final Map<String, Object> requestHeaders = Maps.newHashMap();
        for (final String name : headerNames) {
            if (name == null) {
                continue;
            }
            // converted to lower case for case-insensitive matching (patterns are lower case)
            final String keyLowerCase = name.toLowerCase(Locale.ENGLISH);
            if (!matchesOneOf(keyLowerCase, capturePatterns)) {
                continue;
            }
            if (matchesOneOf(keyLowerCase, maskPatterns)) {
                requestHeaders.put(name, "****");
                continue;
            }
            final List<String> values = headers.get(name);
            if (values != null) {
                captureHeader(name, values, requestHeaders);
            }
        }
        return ImmutableMap.copyOf(requestHeaders);
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
        for (final Pattern pattern : patterns) {
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
            final List<String> list = Lists.newArrayList();
            for (final String value : values) {
                list.add(Strings.nullToEmpty(value));
            }
            header.put(name, ImmutableList.copyOf(list));
        }
    }
}
