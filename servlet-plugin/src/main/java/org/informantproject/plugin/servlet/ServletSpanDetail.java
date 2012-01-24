/**
 * Copyright 2011 the original author or authors.
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

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.SpanContextMap;
import org.informantproject.shaded.google.common.base.Strings;

/**
 * Servlet span captured by AspectJ pointcut.
 * 
 * Similar thread safety issues as {@link JdbcSpanDetail}, see documentation in that class for more
 * info.
 * 
 * This span gets to piggyback on the happens-before relationships created by putting other spans
 * into the concurrent queue which ensures that session state is visible at least up to the start of
 * the most recent span.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class ServletSpanDetail implements RootSpanDetail {

    // whether intercepting a servlet or a filter
    private final boolean filter;
    // the servlet or filter class that is being intercepted
    private final Class<?> clazz;

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
    private final String requestMethod;
    private final String requestURI;
    private volatile Map<String, String[]> requestParameterMap;

    private volatile String username;

    // the initial value is the sessionId as it was present at the beginning of the request
    private final String sessionIdInitialValue;

    private volatile String sessionIdUpdatedValue;

    // session attributes may not be thread safe, so they must be converted to thread-safe Strings
    // within the request processing thread, which can then be used by the stuck trace alerting
    // threads and real-time monitoring threads
    // the initial value map contains the session attributes as they were present at the beginning
    // of the request
    private final Map<String, String> sessionAttributeInitialValueMap;

    private volatile Map<String, String> sessionAttributeUpdatedValueMap;

    ServletSpanDetail(boolean filter, Class<?> clazz, String requestMethod, String requestURI) {
        this(filter, clazz, requestMethod, requestURI, null, null, null);
    }

    ServletSpanDetail(boolean filter, Class<?> clazz, String requestMethod, String requestURI,
            String username, String sessionId, Map<String, String> sessionAttributeMap) {

        this.filter = filter;
        this.clazz = clazz;
        this.requestMethod = requestMethod;
        this.requestURI = requestURI;
        this.username = username;
        this.sessionIdInitialValue = sessionId;
        if (sessionAttributeMap == null || sessionAttributeMap.isEmpty()) {
            this.sessionAttributeInitialValueMap = null;
        } else {
            this.sessionAttributeInitialValueMap = sessionAttributeMap;
        }
    }

    public String getDescription() {
        if (filter) {
            return "filter: " + clazz.getName() + ".doFilter()";
        } else {
            return "servlet: " + clazz.getName() + ".service()";
        }
    }

    public SpanContextMap getContextMap() {
        SpanContextMap context = new SpanContextMap();
        addRequestContext(context);
        addHttpSessionContext(context);
        return context;
    }

    public String getUsername() {
        return username;
    }

    boolean isRequestParameterMapCaptured() {
        return requestParameterMap != null;
    }

    void captureRequestParameterMap(Map<?, ?> requestParameterMap) {
        // shallow copy is necessary because request may not be thread safe
        // shallow copy is also necessary because of the note about tomcat above
        Map<String, String[]> map = new HashMap<String, String[]>(requestParameterMap.size());
        for (Entry<?, ?> entry : requestParameterMap.entrySet()) {
            map.put((String) entry.getKey(), (String[]) entry.getValue());
        }
        this.requestParameterMap = map;
    }

    void setUsername(String username) {
        this.username = username;
    }

    void setSessionIdUpdatedValue(String sessionId) {
        this.sessionIdUpdatedValue = sessionId;
    }

    void putSessionAttributeChangedValue(String name, String value) {
        if (sessionAttributeUpdatedValueMap == null) {
            sessionAttributeUpdatedValueMap = new ConcurrentHashMap<String, String>();
        }
        sessionAttributeUpdatedValueMap.put(name, value);
    }

    String getSessionIdInitialValue() {
        return sessionIdInitialValue;
    }

    String getSessionIdUpdatedValue() {
        return sessionIdUpdatedValue;
    }

    private void addRequestContext(SpanContextMap context) {

        context.put("request method", requestMethod);
        context.put("request uri", requestURI);

        if (requestParameterMap != null && !requestParameterMap.isEmpty()) {
            SpanContextMap nestedContext = new SpanContextMap();
            for (String parameterName : requestParameterMap.keySet()) {
                String[] values = requestParameterMap.get(parameterName);
                for (String value : values) {
                    nestedContext.put(parameterName, value);
                }
            }
            context.put("request parameters", nestedContext);
        }
    }

    private void addHttpSessionContext(SpanContextMap context) {
        if (sessionIdUpdatedValue != null) {
            context.put("session id (at beginning of this request)",
                    Strings.nullToEmpty(sessionIdInitialValue));
            context.put("session id (updated during this request)", sessionIdUpdatedValue);
        } else if (sessionIdInitialValue != null) {
            context.put("session id", sessionIdInitialValue);
        }
        if (sessionAttributeInitialValueMap != null && sessionAttributeUpdatedValueMap == null) {
            // session attributes were captured at the beginning of the request, and no session
            // attributes were updated mid-request
            context.put("session attributes", getSessionAttributeInitialValues());
        } else if (sessionAttributeUpdatedValueMap != null) {
            // session attributes were updated mid-request
            SpanContextMap initialValuesNestedContext = getSessionAttributeInitialValues();
            // add empty values into initial values for any updated attributes that are not already
            // present in initial values nested context
            for (Entry<String, String> entry : sessionAttributeUpdatedValueMap.entrySet()) {
                if (initialValuesNestedContext.containsKey(entry.getKey())
                        && entry.getValue() != null) {
                    initialValuesNestedContext.put(entry.getKey(), entry.getValue());
                }
            }
            context.put("session attributes (at beginning of this request)",
                    initialValuesNestedContext);
            context.put("session attributes (updated during this request)",
                    getSessionAttributeUpdatedValues());
        }
    }

    private SpanContextMap getSessionAttributeInitialValues() {
        // create nested context with session attribute initial values
        SpanContextMap nestedContext = new SpanContextMap();
        for (Entry<String, String> entry : sessionAttributeInitialValueMap.entrySet()) {
            if (entry.getValue() != null) {
                nestedContext.put(entry.getKey(), entry.getValue());
            }
        }
        return nestedContext;
    }

    private SpanContextMap getSessionAttributeUpdatedValues() {
        // create nested context with session attribute updated values
        SpanContextMap nestedContext = new SpanContextMap();
        for (Entry<String, String> entry : sessionAttributeUpdatedValueMap.entrySet()) {
            if (entry.getValue() != null) {
                nestedContext.put(entry.getKey(), entry.getValue());
            }
        }
        return nestedContext;
    }
}
