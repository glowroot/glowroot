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
package org.glowroot.agent.plugin.servlet;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;

// this class is thread safe since it is part of the thread safe ServletMessageSupplier (see comment
// in ServletMessageSupplier for details why)
class ResponseHeaderComponent {

    private static final Logger logger = Agent.getLogger(ServletMessageSupplier.class);

    // HTTP response header date format (RFC 1123)
    // also see org.apache.tomcat.util.http.FastHttpDateFormat
    private static final SimpleDateFormat responseHeaderDateFormat =
            new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);

    static {
        // all HTTP dates are on GMT
        responseHeaderDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    // map key is uppercase for case-insensitivity
    private @MonotonicNonNull ConcurrentMap<String, ResponseHeader> responseHeaders;

    synchronized Map<String, Object> getMapOfStrings() {
        if (responseHeaders == null) {
            return ImmutableMap.of();
        }
        // lazy format int and date headers as strings since this method is only called if
        // it is really needed (for storage or display of active trace)
        Map<String, Object> responseHeaderStrings = Maps.newHashMap();
        for (ResponseHeader responseHeader : responseHeaders.values()) {
            String name = responseHeader.getName();
            List<Object> values = responseHeader.getValues();
            if (values != null) {
                List<String> headerValueStrings = Lists.newArrayList();
                for (Object v : values) {
                    headerValueStrings.add(headerValueString(v));
                }
                responseHeaderStrings.put(name, headerValueStrings);
            } else {
                Object value = responseHeader.getValue();
                responseHeaderStrings.put(name, headerValueString(value));
            }
        }
        return responseHeaderStrings;
    }

    synchronized void setHeader(String name, Object value) {
        if (responseHeaders == null) {
            responseHeaders = Maps.newConcurrentMap();
        }
        String nameUpper = name.toUpperCase(Locale.ENGLISH);
        ResponseHeader responseHeader = responseHeaders.get(nameUpper);
        if (responseHeader == null) {
            responseHeaders.put(nameUpper, new ResponseHeader(name, value));
        } else {
            responseHeader.setValue(value);
        }
    }

    synchronized void addHeader(String name, Object value) {
        if (responseHeaders == null) {
            responseHeaders = Maps.newConcurrentMap();
        }
        String nameUpper = name.toUpperCase(Locale.ENGLISH);
        ResponseHeader responseHeader = responseHeaders.get(nameUpper);
        if (responseHeader == null) {
            responseHeaders.put(nameUpper, new ResponseHeader(name, value));
        } else {
            responseHeader.addValue(value);
        }
    }

    private static String headerValueString(Object value) {
        if (value instanceof String) {
            return (String) value;
        } else if (value instanceof Integer) {
            return Integer.toString((Integer) value);
        } else if (value instanceof Long) {
            // this is only called on traces that need to be stored (or an active trace being viewed
            // inflight in the UI), so date format overhead seems acceptable
            synchronized (responseHeaderDateFormat) {
                return responseHeaderDateFormat.format(new Date((Long) value));
            }
        } else {
            logger.warn("unexpected value type found: {}", value);
            return "<unexpected value type: " + value.getClass() + ">";
        }
    }

    // this class is thread safe since it is part of the thread safe ServletMessageSupplier (see
    // comment in ServletMessageSupplier for details why)
    private static class ResponseHeader {

        private final String name;
        private volatile Object value;
        private volatile @MonotonicNonNull CopyOnWriteArrayList<Object> values;

        private ResponseHeader(String name, Object value) {
            this.name = name;
            this.value = value;
        }

        private String getName() {
            return name;
        }

        private Object getValue() {
            return value;
        }

        private @Nullable List<Object> getValues() {
            return values;
        }

        private void setValue(Object value) {
            this.value = value;
        }

        private void addValue(Object newValue) {
            if (values == null) {
                values = Lists.newCopyOnWriteArrayList();
                values.add(value);
            }
            values.add(newValue);
        }
    }
}
