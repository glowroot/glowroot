/*
 * Copyright 2011-2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

import org.glowroot.api.Message;
import org.glowroot.api.MessageSupplier;

// similar thread safety issues as {@link JdbcMessageSupplier}, see documentation in that class for
// more info
//
// this trace entry gets to piggyback on the happens-before relationships created by putting other
// trace entries into the concurrent queue which ensures that session state is visible at least up
// to the start of the most recent trace entry
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
    private final @Nullable String requestQueryString;

    private volatile @MonotonicNonNull ImmutableMap<String, Object> requestParameters;

    private final ImmutableMap<String, Object> requestHeaders;

    private final ResponseHeaderComponent responseHeaderComponent = new ResponseHeaderComponent();

    // session attributes may not be thread safe, so they must be converted to Strings
    // within the request processing thread, which can then be safely read by the trace storage
    // thread (and live viewing thread also)
    // the initial value map contains the session attributes as they were present at the beginning
    // of the request
    private final ImmutableMap<String, String> sessionAttributeInitialValueMap;

    // ConcurrentHashMap does not allow null values, so need to use Optional values
    private volatile @MonotonicNonNull ConcurrentMap<String, Optional<String>> sessionAttributeUpdatedValueMap;

    ServletMessageSupplier(String requestMethod, String requestUri,
            @Nullable String requestQueryString, ImmutableMap<String, Object> requestHeaders,
            ImmutableMap<String, String> sessionAttributeMap) {
        this.requestMethod = requestMethod;
        this.requestUri = requestUri;
        this.requestQueryString = requestQueryString;
        this.requestHeaders = requestHeaders;
        this.sessionAttributeInitialValueMap = sessionAttributeMap;
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
        Map<String, Object> responseHeaderStrings = responseHeaderComponent.getMapOfStrings();
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

    void setResponseHeader(String name, String value) {
        responseHeaderComponent.setHeader(name, value);
    }

    void setResponseDateHeader(String name, long date) {
        responseHeaderComponent.setHeader(name, date);
    }

    void setResponseIntHeader(String name, int value) {
        responseHeaderComponent.setHeader(name, value);
    }

    void setResponseLongHeader(String name, long value) {
        responseHeaderComponent.setHeader(name, value);
    }

    void addResponseHeader(String name, String value) {
        responseHeaderComponent.addHeader(name, value);
    }

    void addResponseDateHeader(String name, long date) {
        responseHeaderComponent.addHeader(name, date);
    }

    void addResponseIntHeader(String name, int value) {
        responseHeaderComponent.addHeader(name, value);
    }

    void putSessionAttributeChangedValue(String name, @Nullable String value) {
        if (sessionAttributeUpdatedValueMap == null) {
            sessionAttributeUpdatedValueMap = Maps.newConcurrentMap();
        }
        sessionAttributeUpdatedValueMap.put(name, Optional.fromNullable(value));
    }

    private void addSessionAttributeDetail(Map<String, Object> detail) {
        if (!sessionAttributeInitialValueMap.isEmpty()) {
            if (sessionAttributeUpdatedValueMap == null) {
                // session attributes were captured at the beginning of the request, and no session
                // attributes were updated mid-request
                detail.put("Session attributes", sessionAttributeInitialValueMap);
            } else {
                // some session attributes were updated mid-request
                addMidRequestSessionAttributeDetail(detail);
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

    @RequiresNonNull("sessionAttributeUpdatedValueMap")
    private void addMidRequestSessionAttributeDetail(Map<String, Object> detail) {
        Map<String, /*@Nullable*/Object> sessionAttributeInitialValuePlusMap = Maps.newHashMap();
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
}
