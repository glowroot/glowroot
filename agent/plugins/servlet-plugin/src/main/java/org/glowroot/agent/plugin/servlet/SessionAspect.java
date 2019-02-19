/*
 * Copyright 2011-2019 the original author or authors.
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

import java.util.List;
import java.util.Map;

import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.util.Beans;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.servlet._.ServletMessageSupplier;
import org.glowroot.agent.plugin.servlet._.ServletPluginProperties;
import org.glowroot.agent.plugin.servlet._.Strings;
import org.glowroot.agent.plugin.servlet._.ServletPluginProperties.SessionAttributePath;

public class SessionAspect {

    @Pointcut(className = "javax.servlet.http.HttpSession", methodName = "setAttribute|putValue",
            methodParameterTypes = {"java.lang.String", "java.lang.Object"},
            nestingGroup = "servlet-inner-call")
    public static class SetAttributeAdvice {

        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter @Nullable String name,
                @BindParameter @Nullable Object value) {
            if (name == null) {
                // theoretically possible, so just ignore
                return;
            }
            // name is non-null per HttpSession.setAttribute() javadoc, but value may be null
            // (which per the javadoc is the same as calling removeAttribute())
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                updateUserIfApplicable(context, name, value);
                updateSessionAttributesIfApplicable(messageSupplier, name, value);
            }
        }

        private static void updateUserIfApplicable(ThreadContext context, String attributeName,
                @Nullable Object attributeValue) {
            if (attributeValue == null) {
                // if user value is set to null, don't clear it
                return;
            }
            SessionAttributePath userAttributePath = ServletPluginProperties.userAttributePath();
            if (userAttributePath == null) {
                return;
            }
            if (!userAttributePath.getAttributeName().equals(attributeName)) {
                return;
            }
            // capture user now, don't use a lazy supplier
            List<String> nestedPath = userAttributePath.getNestedPath();
            if (nestedPath.isEmpty()) {
                context.setTransactionUser(attributeValue.toString(), Priority.CORE_PLUGIN);
            } else {
                Object user;
                try {
                    user = Beans.value(attributeValue, nestedPath);
                } catch (Exception e) {
                    user = "<could not access: " + e + ">";
                }
                if (user != null) {
                    // if user is null, don't clear it
                    context.setTransactionUser(user.toString(), Priority.CORE_PLUGIN);
                }
            }
        }

        private static void updateSessionAttributesIfApplicable(
                ServletMessageSupplier messageSupplier, String attributeName,
                @Nullable Object attributeValue) {
            if (ServletPluginProperties.captureSessionAttributeNames().contains(attributeName)
                    || ServletPluginProperties.captureSessionAttributeNames().contains("*")) {
                // update all session attributes (possibly nested) at or under the set attribute
                for (SessionAttributePath attributePath : ServletPluginProperties
                        .captureSessionAttributePaths()) {
                    if (attributePath.getAttributeName().equals(attributeName)
                            || attributePath.isAttributeNameWildcard()) {
                        if (attributePath.getNestedPath().isEmpty()
                                && !attributePath.isWildcard()) {
                            updateSessionAttribute(messageSupplier, attributeName, attributeValue);
                        } else {
                            updateNestedSessionAttributes(messageSupplier, attributePath,
                                    attributeValue);
                        }
                    }
                }
            }
        }

        private static void updateSessionAttribute(ServletMessageSupplier messageSupplier,
                String attributeName, @Nullable Object attributeValue) {
            if (attributeValue == null) {
                messageSupplier.putSessionAttributeChangedValue(attributeName, null);
            } else {
                messageSupplier.putSessionAttributeChangedValue(attributeName,
                        Strings.nullToEmpty(attributeValue.toString()));
            }
        }

        private static void updateNestedSessionAttributes(ServletMessageSupplier messageSupplier,
                SessionAttributePath attributePath, @Nullable Object attributeValue) {
            String fullPath = attributePath.getFullPath();
            if (attributePath.isWildcard()) {
                Object val = HttpSessions.getSessionAttribute(attributeValue, attributePath);
                if (val == null) {
                    messageSupplier.putSessionAttributeChangedValue(fullPath, null);
                } else if (val instanceof Map<?, ?>) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) val).entrySet()) {
                        Object v = entry.getValue();
                        messageSupplier.putSessionAttributeChangedValue(
                                fullPath + "." + entry.getKey(),
                                v == null ? null : Strings.nullToEmpty(v.toString()));
                    }
                } else {
                    for (Map.Entry<String, String> entry : Beans.propertiesAsText(val).entrySet()) {
                        messageSupplier.putSessionAttributeChangedValue(
                                fullPath + "." + entry.getKey(), entry.getValue());
                    }
                }
            } else if (attributeValue == null) {
                // no need to navigate path since it will always be null
                messageSupplier.putSessionAttributeChangedValue(fullPath, null);
            } else {
                Object val = HttpSessions.getSessionAttribute(attributeValue, attributePath);
                messageSupplier.putSessionAttributeChangedValue(fullPath,
                        val == null ? null : Strings.nullToEmpty(val.toString()));
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpSession", methodName = "removeAttribute",
            methodParameterTypes = {"java.lang.String"}, nestingGroup = "servlet-inner-call")
    public static class RemoveAttributeAdvice {
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter @Nullable String name) {
            // calling HttpSession.setAttribute() with null value is the same as calling
            // removeAttribute(), per the setAttribute() javadoc
            SetAttributeAdvice.onAfter(context, name, null);
        }
    }
}
