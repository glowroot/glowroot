/*
 * Copyright 2012-2014 the original author or authors.
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

import java.util.Enumeration;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import org.glowroot.api.Beans;
import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;
import org.glowroot.api.UnresolvedMethod;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// HttpSession wrapper does not make assumptions about the @Nullable properties of the underlying
// javax.servlet.http.HttpSession since it's just an interface and could theoretically return null
// even where it seems to not make sense
class HttpSession {

    private static final Logger logger = LoggerFactory.getLogger(HttpSession.class);

    private static final UnresolvedMethod getIdMethod =
            UnresolvedMethod.from("javax.servlet.http.HttpSession", "getId");
    private static final UnresolvedMethod isNewMethod =
            UnresolvedMethod.from("javax.servlet.http.HttpSession", "isNew");
    private static final UnresolvedMethod getAttributeNamesMethod =
            UnresolvedMethod.from("javax.servlet.http.HttpSession", "getAttributeNames");
    private static final UnresolvedMethod getAttributeMethod =
            UnresolvedMethod.from("javax.servlet.http.HttpSession", "getAttribute", String.class);

    private final Object realSession;

    static HttpSession from(Object realSession) {
        return new HttpSession(realSession);
    }

    private HttpSession(Object realSession) {
        this.realSession = realSession;
    }

    String getId() {
        String id = (String) getIdMethod.invoke(realSession, "");
        if (id == null) {
            return "";
        } else {
            return id;
        }
    }

    boolean isNew() {
        Boolean isNew = (Boolean) isNewMethod.invoke(realSession, false);
        if (isNew == null) {
            logger.warn("method unexpectedly returned null:"
                    + " javax.servlet.http.HttpSession.isNew()");
            return false;
        }
        return isNew;
    }

    @Nullable
    ImmutableMap<String, String> getSessionAttributes() {
        Set<String> capturePaths = ServletPluginProperties.captureSessionAttributePaths();
        if (capturePaths.isEmpty()) {
            return null;
        }
        ImmutableMap.Builder<String, String> captureMap = ImmutableMap.builder();
        // dump only http session attributes in list
        for (String capturePath : capturePaths) {
            if (capturePath.equals("*")) {
                for (Enumeration<?> e = getAttributeNames(); e.hasMoreElements();) {
                    String attributeName = (String) e.nextElement();
                    if (attributeName == null) {
                        // null check to be safe in case this is a very strange servlet container
                        continue;
                    }
                    Object value = getAttribute(attributeName);
                    // value shouldn't be null, but its (remotely) possible that a concurrent
                    // request for the same session just removed the attribute
                    String valueString = value == null ? "" : value.toString();
                    // taking no chances on value.toString() possibly returning null
                    captureMap.put(attributeName, Strings.nullToEmpty(valueString));
                }
            } else if (capturePath.endsWith(".*")) {
                capturePath = capturePath.substring(0, capturePath.length() - 2);
                Object value = getSessionAttribute(capturePath);
                if (value != null) {
                    for (Entry<String, String> entry : Beans.propertiesAsText(value).entrySet()) {
                        captureMap.put(capturePath + "." + entry.getKey(), entry.getValue());
                    }
                }
            } else {
                String value = getSessionAttributeTextValue(capturePath);
                if (value != null) {
                    captureMap.put(capturePath, value);
                }
            }
        }
        return captureMap.build();
    }

    @Nullable
    String getSessionAttributeTextValue(String attributePath) {
        Object value = getSessionAttribute(attributePath);
        return (value == null) ? null : value.toString();
    }

    @Nullable
    Object getSessionAttribute(String attributePath) {
        int index = attributePath.indexOf('.');
        if (index == -1) {
            // fast path
            return getAttribute(attributePath);
        } else {
            Object curr = getAttribute(attributePath.substring(0, index));
            return Beans.value(curr, attributePath.substring(index + 1));
        }
    }

    private Enumeration<?> getAttributeNames() {
        Enumeration<?> attributeNames =
                (Enumeration<?>) getAttributeNamesMethod.invoke(realSession, null);
        if (attributeNames == null) {
            return EmptyEnumeration.INSTANCE;
        } else {
            return attributeNames;
        }
    }

    @Nullable
    private Object getAttribute(String name) {
        return getAttributeMethod.invoke(realSession, name,
                "<error calling HttpSession.getAttribute()>");
    }

    @Immutable
    private static class EmptyEnumeration implements Enumeration<Object> {
        private static final Enumeration<Object> INSTANCE = new EmptyEnumeration();
        @Override
        public boolean hasMoreElements() {
            return false;
        }
        @Override
        public Object nextElement() {
            throw new NoSuchElementException();
        }
    }
}
