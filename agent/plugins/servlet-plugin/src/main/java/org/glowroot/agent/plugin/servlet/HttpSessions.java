/*
 * Copyright 2012-2017 the original author or authors.
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

import java.util.Enumeration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.glowroot.agent.plugin.servlet.ServletAspect.HttpSession;

class HttpSessions {

    private HttpSessions() {}

    static ImmutableMap<String, String> getSessionAttributes(HttpSession session) {
        Set<String> capturePaths = ServletPluginProperties.captureSessionAttributePaths();
        if (capturePaths.isEmpty()) {
            return ImmutableMap.of();
        }
        Map<String, String> captureMap = Maps.newHashMap();
        // dump only http session attributes in list
        for (String capturePath : capturePaths) {
            if (capturePath.equals("*")) {
                captureAllSessionAttributes(session, captureMap);
            } else if (capturePath.endsWith(".*")) {
                captureWildcardPath(session, captureMap,
                        capturePath.substring(0, capturePath.length() - 2));
            } else {
                captureNonWildcardPath(session, captureMap, capturePath);
            }
        }
        return ImmutableMap.copyOf(captureMap);
    }

    static @Nullable String getSessionAttributeTextValue(HttpSession session,
            String attributePath) {
        Object value = getSessionAttribute(session, attributePath);
        return (value == null) ? null : value.toString();
    }

    static @Nullable Object getSessionAttribute(HttpSession session, String attributePath) {
        if (attributePath.equals(ServletPluginProperties.HTTP_SESSION_ID_ATTR)) {
            return session.getId();
        }
        int index = attributePath.indexOf('.');
        if (index == -1) {
            // fast path
            return session.getAttribute(attributePath);
        } else {
            Object curr = session.getAttribute(attributePath.substring(0, index));
            return Beans.value(curr, attributePath.substring(index + 1));
        }
    }

    private static void captureAllSessionAttributes(HttpSession session,
            Map<String, String> captureMap) {
        Enumeration<? extends /*@Nullable*/ Object> e = session.getAttributeNames();
        if (e == null) {
            return;
        }
        while (e.hasMoreElements()) {
            String attributeName = (String) e.nextElement();
            if (attributeName == null) {
                continue;
            }
            String valueString;
            if (attributeName.equals(ServletPluginProperties.HTTP_SESSION_ID_ATTR)) {
                valueString = Strings.nullToEmpty(session.getId());
            } else {
                Object value = session.getAttribute(attributeName);
                // value shouldn't be null, but its (remotely) possible that a concurrent
                // request for the same session just removed the attribute
                valueString = value == null ? "" : value.toString();
            }
            // taking no chances on value.toString() possibly returning null
            captureMap.put(attributeName, Strings.nullToEmpty(valueString));
        }
    }

    private static void captureWildcardPath(HttpSession session, Map<String, String> captureMap,
            String capturePath) {
        Object value = getSessionAttribute(session, capturePath);
        if (value instanceof Map<?, ?>) {
            for (Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object val = entry.getValue();
                captureMap.put(capturePath + "." + entry.getKey(),
                        val == null ? "" : val.toString());
            }
        } else if (value != null) {
            for (Entry<String, String> entry : Beans.propertiesAsText(value).entrySet()) {
                captureMap.put(capturePath + "." + entry.getKey(), entry.getValue());
            }
        }
    }

    private static void captureNonWildcardPath(HttpSession session, Map<String, String> captureMap,
            String capturePath) {
        String value = getSessionAttributeTextValue(session, capturePath);
        if (value != null) {
            captureMap.put(capturePath, value);
        }
    }
}
