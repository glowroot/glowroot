/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.plugin.servlet;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;

class ResponseHeaders {

    private static final Logger logger = LoggerFactory.getLogger(ServletMessageSupplier.class);

    // HTTP response header date format (RFC 1123)
    // also see org.apache.tomcat.util.http.FastHttpDateFormat
    private static final SimpleDateFormat responseHeaderDateFormat =
            new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);

    static {
        // all HTTP dates are on GMT
        responseHeaderDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    // map key is uppercase for case-insensitivity
    // implementation is LinkedHashMap to preserve insertion order
    @MonotonicNonNull
    private Map<String, ResponseHeader> responseHeaders;

    synchronized Map<String, Object> getMapOfStrings() {
        if (responseHeaders == null) {
            return ImmutableMap.of();
        }
        // lazy format int and date headers as strings since this method is only called if
        // it is really needed (for storage or display of active trace)
        Map<String, Object> responseHeaderStrings = Maps.newHashMap();
        for (ResponseHeader responseHeader : responseHeaders.values()) {
            String name = responseHeader.getName();
            Object value = responseHeader.getValue();
            if (value instanceof List) {
                List<String> values = Lists.newArrayList();
                for (Object v : (List<?>) value) {
                    values.add(headerValueString(v));
                }
                responseHeaderStrings.put(name, values);
            } else {
                responseHeaderStrings.put(name, headerValueString(value));
            }
        }
        return responseHeaderStrings;
    }

    synchronized void setHeader(String name, Object value) {
        if (responseHeaders == null) {
            responseHeaders = Maps.newLinkedHashMap();
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
            responseHeaders = Maps.newLinkedHashMap();
        }
        String nameUpper = name.toUpperCase(Locale.ENGLISH);
        ResponseHeader responseHeader = responseHeaders.get(nameUpper);
        if (responseHeader == null) {
            responseHeaders.put(nameUpper, new ResponseHeader(name, value));
        } else {
            Object existingValue = responseHeader.getValue();
            if (existingValue instanceof List) {
                List<Object> updatedValues = Lists.newArrayList();
                updatedValues.addAll((List<?>) existingValue);
                updatedValues.add(value);
                responseHeader.setValue(ImmutableList.copyOf(updatedValues));
            } else {
                responseHeader.setValue(ImmutableList.of(existingValue, value));
            }
        }
    }

    private String headerValueString(Object value) {
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

    private static class ResponseHeader {
        private final String name;
        private volatile Object value;
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
        private void setValue(Object value) {
            this.value = value;
        }
    }
}
