/*
 * Copyright 2012-2019 the original author or authors.
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

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpSession;

import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.util.Beans;
import org.glowroot.agent.plugin.api.util.ImmutableMap;
import org.glowroot.agent.plugin.servlet._.ServletMessageSupplier;
import org.glowroot.agent.plugin.servlet._.ServletPluginProperties;
import org.glowroot.agent.plugin.servlet._.ServletPluginProperties.SessionAttributePath;
import org.glowroot.agent.plugin.servlet._.Strings;

public class HttpSessions {

    private HttpSessions() {}

    public static Map<String, String> getSessionAttributes(HttpSession session) {
        List<SessionAttributePath> attributePaths =
                ServletPluginProperties.captureSessionAttributePaths();
        if (attributePaths.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> captureMap = new HashMap<String, String>();
        // dump only http session attributes in list
        for (SessionAttributePath attributePath : attributePaths) {
            if (attributePath.isAttributeNameWildcard()) {
                captureAllSessionAttributes(session, captureMap);
            } else if (attributePath.isWildcard()) {
                captureWildcardPath(session, captureMap, attributePath);
            } else {
                captureNonWildcardPath(session, captureMap, attributePath);
            }
        }
        return ImmutableMap.copyOf(captureMap);
    }

    public static @Nullable Object getSessionAttribute(HttpSession session,
            SessionAttributePath attributePath) {
        if (attributePath.isSessionId()) {
            return session.getId();
        }
        Object attributeValue = session.getAttribute(attributePath.getAttributeName());
        return getSessionAttribute(attributeValue, attributePath);
    }

    public static @Nullable Object getSessionAttribute(@Nullable Object attributeValue,
            SessionAttributePath attributePath) {
        List<String> nestedPath = attributePath.getNestedPath();
        if (nestedPath.isEmpty()) {
            return attributeValue;
        } else {
            try {
                return Beans.value(attributeValue, nestedPath);
            } catch (Exception e) {
                return "<error evaluating: " + e + ">";
            }
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
            if (attributeName.equals(ServletPluginProperties.HTTP_SESSION_ID_ATTR)) {
                captureMap.put(attributeName, Strings.nullToEmpty(session.getId()));
                continue;
            }
            Object valueObj = session.getAttribute(attributeName);
            if (valueObj == null) {
                // value shouldn't be null, but its (remotely) possible that a concurrent
                // request for the same session just removed the attribute
                continue;
            }
            String value;
            try {
                value = Strings.nullToEmpty(valueObj.toString());
            } catch (Exception f) {
                value = "<error evaluating: " + f + ">";
            }
            captureMap.put(attributeName, value);
        }
    }

    private static void captureWildcardPath(HttpSession session, Map<String, String> captureMap,
            SessionAttributePath attributePath) {
        Object value = getSessionAttribute(session, attributePath);
        if (value instanceof Map<?, ?>) {
            String fullPath = attributePath.getFullPath();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object val = entry.getValue();
                captureMap.put(fullPath + "." + entry.getKey(),
                        val == null ? "" : Strings.nullToEmpty(val.toString()));
            }
        } else if (value != null) {
            String fullPath = attributePath.getFullPath();
            for (Map.Entry<String, String> entry : Beans.propertiesAsText(value).entrySet()) {
                captureMap.put(fullPath + "." + entry.getKey(), entry.getValue());
            }
        }
    }

    private static void captureNonWildcardPath(HttpSession session, Map<String, String> captureMap,
            SessionAttributePath attributePath) {
        Object value = getSessionAttribute(session, attributePath);
        if (value != null) {
            captureMap.put(attributePath.getFullPath(), Strings.nullToEmpty(value.toString()));
        }
    }
}
