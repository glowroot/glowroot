/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.plugin.servlet;

import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.informantproject.api.Message;
import org.informantproject.api.Supplier;
import org.informantproject.shaded.google.common.base.Objects;
import org.informantproject.shaded.google.common.base.Optional;
import org.informantproject.shaded.google.common.collect.ImmutableMap;
import org.informantproject.shaded.google.common.collect.Maps;

/**
 * Servlet span captured by AspectJ pointcut.
 * 
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
class ServletMessageSupplier extends Supplier<Message> {

    // TODO allow additional notation for session attributes to capture, e.g.
    // +currentControllerContext.key which would denote to capture the value of that attribute at
    // the beginning of the request

    // it would be convenient to just store the request object here
    // but it appears that tomcat (at least, maybe others) clears out those
    // objects after the response is complete so that it can reuse the
    // request object for future requests
    // since the data is persisted in a separate thread to avoid slowing up the user's
    // request, the request object could have been cleared before the request data
    // is persisted, so instead the request parts that are needed must be cached
    //
    // another problem with storing the request object here is that it may not be thread safe
    //
    // the http session object also cannot be stored here because it may be marked
    // expired when the session attributes are persisted, so instead
    // (references to) the session attributes must be stored here

    @Nullable
    private final String requestMethod;
    @Nullable
    private final String requestURI;
    @Nullable
    private volatile Map<String, String[]> requestParameterMap;

    // the initial value is the sessionId as it was present at the beginning of the request
    @Nullable
    private final String sessionIdInitialValue;
    @Nullable
    private volatile String sessionIdUpdatedValue;

    // session attributes may not be thread safe, so they must be converted to Strings
    // within the request processing thread, which can then be used by the stuck trace alerting
    // threads and real-time monitoring threads
    // the initial value map contains the session attributes as they were present at the beginning
    // of the request
    @Nullable
    private final Map<String, String> sessionAttributeInitialValueMap;

    // ConcurrentHashMap does not allow null values, so need to use Optional values
    @Nullable
    private volatile Map<String, Optional<String>> sessionAttributeUpdatedValueMap;

    ServletMessageSupplier(@Nullable String requestMethod, @Nullable String requestURI,
            @Nullable String sessionId, @Nullable Map<String, String> sessionAttributeMap) {

        this.requestMethod = requestMethod;
        this.requestURI = requestURI;
        this.sessionIdInitialValue = sessionId;
        if (sessionAttributeMap == null || sessionAttributeMap.isEmpty()) {
            this.sessionAttributeInitialValueMap = null;
        } else {
            this.sessionAttributeInitialValueMap = sessionAttributeMap;
        }
    }

    @Override
    public Message get() {
        ImmutableMap.Builder<String, Object> mapBuilder = ImmutableMap.builder();
        addRequestParametersContextMap(mapBuilder);
        addSessionAttributesContextMap(mapBuilder);
        String message;
        if (requestMethod == null && requestURI == null) {
            message = "";
        } else if (requestMethod == null) {
            message = Objects.firstNonNull(requestURI, "");
        } else if (requestURI == null) {
            message = Objects.firstNonNull(requestMethod, "");
        } else {
            message = requestMethod + " " + requestURI;
        }
        return Message.withContextMap(message, mapBuilder.build());
    }

    boolean isRequestParameterMapCaptured() {
        return requestParameterMap != null;
    }

    void captureRequestParameterMap(Map<?, ?> requestParameterMap) {
        // shallow copy is necessary because request may not be thread safe
        // shallow copy is also necessary because of the note about tomcat above
        Map<String, String[]> map = Maps.newHashMapWithExpectedSize(requestParameterMap.size());
        for (Entry<?, ?> entry : requestParameterMap.entrySet()) {
            map.put((String) entry.getKey(), (String[]) entry.getValue());
        }
        this.requestParameterMap = map;
    }

    void setSessionIdUpdatedValue(String sessionId) {
        this.sessionIdUpdatedValue = sessionId;
    }

    void putSessionAttributeChangedValue(String name, @Nullable String value) {
        if (sessionAttributeUpdatedValueMap == null) {
            sessionAttributeUpdatedValueMap = new ConcurrentHashMap<String, Optional<String>>();
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

    private void addRequestParametersContextMap(ImmutableMap.Builder<String, Object> contextMap) {
        if (requestParameterMap != null && !requestParameterMap.isEmpty()) {
            ImmutableMap.Builder<String, Object> nestedContextMap = ImmutableMap.builder();
            for (String parameterName : requestParameterMap.keySet()) {
                String[] values = requestParameterMap.get(parameterName);
                for (String value : values) {
                    nestedContextMap.put(parameterName, value);
                }
            }
            contextMap.put("request parameters", nestedContextMap.build());
        }
    }

    private void addSessionAttributesContextMap(ImmutableMap.Builder<String, Object> contextMap) {
        if (sessionIdUpdatedValue != null) {
            contextMap.put("session id (at beginning of this request)",
                    Optional.fromNullable(sessionIdInitialValue));
            contextMap.put("session id (updated during this request)", sessionIdUpdatedValue);
        } else if (sessionIdInitialValue != null) {
            contextMap.put("session id", sessionIdInitialValue);
        }
        if (sessionAttributeInitialValueMap != null && sessionAttributeUpdatedValueMap == null) {
            // session attributes were captured at the beginning of the request, and no session
            // attributes were updated mid-request
            contextMap.put("session attributes", sessionAttributeInitialValueMap);
        } else if (sessionAttributeInitialValueMap == null
                && sessionAttributeUpdatedValueMap != null) {
            // no session attributes were available at the beginning of the request, and session
            // attributes were updated mid-request
            contextMap.put("session attributes (updated during this request)",
                    sessionAttributeUpdatedValueMap);
        } else if (sessionAttributeUpdatedValueMap != null) {
            // session attributes were updated mid-request
            ImmutableMap.Builder<String, Object> sessionAttributeInitialValuePlusMap =
                    ImmutableMap.builder();
            sessionAttributeInitialValuePlusMap.putAll(sessionAttributeInitialValueMap);
            // add empty values into initial values for any updated attributes that are not already
            // present in initial values nested context map
            for (Entry<String, Optional<String>> entry : sessionAttributeUpdatedValueMap
                    .entrySet()) {
                if (!sessionAttributeInitialValueMap.containsKey(entry.getKey())) {
                    sessionAttributeInitialValuePlusMap.put(entry.getKey(), Optional.absent());
                }
            }
            contextMap.put("session attributes (at beginning of this request)",
                    sessionAttributeInitialValuePlusMap.build());
            contextMap.put("session attributes (updated during this request)",
                    sessionAttributeUpdatedValueMap);
        } else {
            // both initial and updated value maps are null so there is nothing to add to the
            // context map
        }
    }
}
