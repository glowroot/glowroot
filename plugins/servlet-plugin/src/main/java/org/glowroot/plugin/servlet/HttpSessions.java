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
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

class HttpSessions {

    @Nullable
    static ImmutableMap<String, String> getSessionAttributes(Object session,
            SessionInvoker sessionInvoker) {
        Set<String> capturePaths = ServletPluginProperties.captureSessionAttributePaths();
        if (capturePaths.isEmpty()) {
            return null;
        }
        ImmutableMap.Builder<String, String> captureMap = ImmutableMap.builder();
        // dump only http session attributes in list
        for (String capturePath : capturePaths) {
            if (capturePath.equals("*")) {
                Enumeration<?> e = sessionInvoker.getAttributeNames(session);
                while (e.hasMoreElements()) {
                    String attributeName = (String) e.nextElement();
                    if (attributeName == null) {
                        // null check to be safe in case this is a very strange servlet container
                        continue;
                    }
                    Object value = sessionInvoker.getAttribute(session, attributeName);
                    // value shouldn't be null, but its (remotely) possible that a concurrent
                    // request for the same session just removed the attribute
                    String valueString = value == null ? "" : value.toString();
                    // taking no chances on value.toString() possibly returning null
                    captureMap.put(attributeName, Strings.nullToEmpty(valueString));
                }
            } else if (capturePath.endsWith(".*")) {
                capturePath = capturePath.substring(0, capturePath.length() - 2);
                Object value = getSessionAttribute(session, capturePath, sessionInvoker);
                if (value != null) {
                    for (Entry<String, String> entry : Beans.propertiesAsText(value).entrySet()) {
                        captureMap.put(capturePath + "." + entry.getKey(), entry.getValue());
                    }
                }
            } else {
                String value = getSessionAttributeTextValue(session, capturePath, sessionInvoker);
                if (value != null) {
                    captureMap.put(capturePath, value);
                }
            }
        }
        return captureMap.build();
    }
    @Nullable
    static String getSessionAttributeTextValue(Object session, String attributePath,
            SessionInvoker sessionInvoker) {
        Object value = getSessionAttribute(session, attributePath, sessionInvoker);
        return (value == null) ? null : value.toString();
    }

    @Nullable
    static Object getSessionAttribute(Object session, String attributePath,
            SessionInvoker sessionInvoker) {
        int index = attributePath.indexOf('.');
        if (index == -1) {
            // fast path
            return sessionInvoker.getAttribute(session, attributePath);
        } else {
            Object curr = sessionInvoker.getAttribute(session, attributePath.substring(0, index));
            return Beans.value(curr, attributePath.substring(index + 1));
        }
    }
}
