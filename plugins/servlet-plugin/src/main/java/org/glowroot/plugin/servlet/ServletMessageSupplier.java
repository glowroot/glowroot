/*
 * Copyright 2011-2014 the original author or authors.
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

import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;

import checkers.nullness.quals.MonotonicNonNull;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.glowroot.api.Message;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.Optional;

/**
 * Similar thread safety issues as {@link JdbcMessageSupplier}, see documentation in that class for
 * more info.
 * 
 * This span gets to piggyback on the happens-before relationships created by putting other spans
 * into the concurrent queue which ensures that session state is visible at least up to the start of
 * the most recent span.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class ServletMessageSupplier extends MessageSupplier {

    // it would be convenient to just store the request object here
    // but it appears that tomcat (at least, maybe others) clears out those
    // objects after the response is complete so that it can reuse the
    // request object for future requests
    // since the data is stored in a separate thread to avoid slowing up the user's
    // request, the request object could have been cleared before the request data
    // is stored, so instead the request parts that are needed must be cached
    //
    // another problem with storing the request object here is that it may not be thread safe
    //
    // the http session object also cannot be stored here because it may be marked
    // expired when the session attributes are stored, so instead
    // (references to) the session attributes must be stored here

    private final String requestMethod;
    private final String requestUri;
    @Nullable
    private final String requestQueryString;

    @MonotonicNonNull
    private volatile ImmutableMap<String, Object> requestParameters;

    private final ImmutableMap<String, Object> requestHeaders;

    private final ResponseHeaders responseHeaders = new ResponseHeaders();

    // the initial value is the sessionId as it was present at the beginning of the request
    @Nullable
    private final String sessionIdInitialValue;
    @MonotonicNonNull
    private volatile String sessionIdUpdatedValue;

    // session attributes may not be thread safe, so they must be converted to Strings
    // within the request processing thread, which can then be used by the stuck trace alerting
    // threads and real-time monitoring threads
    // the initial value map contains the session attributes as they were present at the beginning
    // of the request
    @Nullable
    private final ImmutableMap<String, String> sessionAttributeInitialValueMap;

    // ConcurrentHashMap does not allow null values, so need to use Optional values
    @MonotonicNonNull
    private volatile ConcurrentMap<String, Optional<String>> sessionAttributeUpdatedValueMap;

    ServletMessageSupplier(String requestMethod, String requestUri,
            @Nullable String requestQueryString, ImmutableMap<String, Object> requestHeaders,
            @Nullable String sessionId,
            @Nullable ImmutableMap<String, String> sessionAttributeMap) {
        this.requestMethod = requestMethod;
        this.requestUri = requestUri;
        this.requestQueryString = requestQueryString;
        this.requestHeaders = requestHeaders;
        this.sessionIdInitialValue = sessionId;
        if (sessionAttributeMap == null || sessionAttributeMap.isEmpty()) {
            this.sessionAttributeInitialValueMap = null;
        } else {
            this.sessionAttributeInitialValueMap = sessionAttributeMap;
        }
    }

    @Override
    public Message get() {
        Map<String, Object> detail = Maps.newHashMap();
        detail.put("Request http method", requestMethod);
        if (requestQueryString != null) {
            // including empty query string since that means request ended with ?
            detail.put("Request query string", requestQueryString);
        }
        if (requestParameters != null && !requestParameters.isEmpty()) {
            detail.put("Request parameters", requestParameters);
        }
        if (!requestHeaders.isEmpty()) {
            detail.put("Request headers", requestHeaders);
        }
        Map<String, Object> responseHeaderStrings = responseHeaders.getMapOfStrings();
        if (!responseHeaderStrings.isEmpty()) {
            detail.put("Response headers", responseHeaderStrings);
        }
        addSessionAttributeDetail(detail);
        return Message.withDetail(requestUri, detail);
    }

    boolean isRequestParametersCaptured() {
        return requestParameters != null;
    }

    void setCaptureRequestParameters(ImmutableMap<String, Object> requestParameters) {
        this.requestParameters = requestParameters;
    }

    void setResponseHeader(String name, @Nullable String value) {
        if (name.equalsIgnoreCase("Content-Type")) {
            // TODO SERVLET 2.4
        }
        if (value == null) {
            responseHeaders.setHeader(name, "");
        } else {
            responseHeaders.setHeader(name, value);
        }
    }

    void setResponseDateHeader(String name, long date) {
        responseHeaders.setHeader(name, date);
    }

    void setResponseIntHeader(String name, int value) {
        responseHeaders.setHeader(name, value);
    }

    void addResponseHeader(String name, @Nullable String value) {
        if (value == null) {
            responseHeaders.addHeader(name, "");
        } else {
            responseHeaders.addHeader(name, value);
        }
    }

    void addResponseDateHeader(String name, long date) {
        responseHeaders.addHeader(name, date);
    }

    void addResponseIntHeader(String name, int value) {
        responseHeaders.addHeader(name, value);
    }

    void updateResponseContentType() {
        // this requires at least Servlet 2.4 (e.g. Tomcat 5.5.x)

        responseHeaders.setHeader("Content-Type", "");
    }

    void setSessionIdUpdatedValue(String sessionId) {
        this.sessionIdUpdatedValue = sessionId;
    }

    void putSessionAttributeChangedValue(String name, @Nullable String value) {
        if (sessionAttributeUpdatedValueMap == null) {
            sessionAttributeUpdatedValueMap = Maps.newConcurrentMap();
        }
        sessionAttributeUpdatedValueMap.put(name, Optional.fromNullable(value));
    }

    @Nullable
    String getSessionIdInitialValue() {
        return sessionIdInitialValue;
    }

    @Nullable
    String getSessionIdUpdatedValue() {
        return sessionIdUpdatedValue;
    }

    private void addSessionAttributeDetail(Map<String, Object> detail) {
        if (ServletPluginProperties.captureSessionId()) {
            if (sessionIdUpdatedValue != null) {
                detail.put("Session ID (at beginning of this request)",
                        Strings.nullToEmpty(sessionIdInitialValue));
                detail.put("Session ID (updated during this request)", sessionIdUpdatedValue);
            } else if (sessionIdInitialValue != null) {
                detail.put("Session ID", sessionIdInitialValue);
            }
        }
        if (sessionAttributeInitialValueMap != null) {
            if (sessionAttributeUpdatedValueMap == null) {
                // session attributes were captured at the beginning of the request, and no session
                // attributes were updated mid-request
                detail.put("Session attributes", sessionAttributeInitialValueMap);
            } else {
                // session attributes were updated mid-request
                Map<String, /*@Nullable*/Object> sessionAttributeInitialValuePlusMap =
                        Maps.newHashMap();
                sessionAttributeInitialValuePlusMap.putAll(sessionAttributeInitialValueMap);
                // add empty values into initial values for any updated attributes that are not
                // already present in initial values nested detail map
                for (Entry<String, Optional<String>> entry : sessionAttributeUpdatedValueMap
                        .entrySet()) {
                    if (!sessionAttributeInitialValueMap.containsKey(entry.getKey())) {
                        sessionAttributeInitialValuePlusMap.put(entry.getKey(), null);
                    }
                }
                detail.put("Session attributes (at beginning of this request)",
                        sessionAttributeInitialValuePlusMap);
                detail.put("Session attributes (updated during this request)",
                        sessionAttributeUpdatedValueMap);
            }
        } else if (sessionAttributeUpdatedValueMap != null) {
            // no session attributes were available at the beginning of the request, and session
            // attributes were updated mid-request
            detail.put("Session attributes (updated during this request)",
                    sessionAttributeUpdatedValueMap);
        } else {
            // both initial and updated value maps are null so there is nothing to add to the
            // detail map
        }
    }
}
