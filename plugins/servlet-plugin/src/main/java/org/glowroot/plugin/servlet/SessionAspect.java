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

import java.util.Map.Entry;

import javax.annotation.Nullable;

import org.glowroot.api.PluginServices;
import org.glowroot.api.weaving.BindClassMeta;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.Pointcut;

public class SessionAspect {

    private static final PluginServices pluginServices = PluginServices.get("servlet");

    /*
     * ================== Http Session Attributes ==================
     */

    @Pointcut(className = "javax.servlet.http.HttpSession", methodName = "setAttribute|putValue",
            methodParameterTypes = {"java.lang.String", "java.lang.Object"})
    public static class SetAttributeAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnAfter
        public static void onAfter(@BindReceiver Object session,
                @BindParameter @Nullable String name, @BindParameter @Nullable Object value,
                @BindClassMeta SessionInvoker sessionInvoker) {
            if (name == null) {
                // theoretically possible, so just ignore
                return;
            }
            // name is non-null per HttpSession.setAttribute() javadoc, but value may be null
            // (which per the javadoc is the same as calling removeAttribute())
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                updateUserIfApplicable(name, value, session, sessionInvoker);
                updateSessionAttributesIfApplicable(messageSupplier, name, value, session,
                        sessionInvoker);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpSession", methodName = "removeAttribute",
            methodParameterTypes = {"java.lang.String"})
    public static class RemoveAttributeAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnAfter
        public static void onAfter(@BindReceiver Object realSession,
                @BindParameter @Nullable String name,
                @BindClassMeta SessionInvoker sessionInvoker) {
            // calling HttpSession.setAttribute() with null value is the same as calling
            // removeAttribute(), per the setAttribute() javadoc
            SetAttributeAdvice.onAfter(realSession, name, null, sessionInvoker);
        }
    }

    private static void updateUserIfApplicable(String name, @Nullable Object value,
            Object session, SessionInvoker sessionInvoker) {
        if (value == null) {
            // if user value is set to null, don't clear it
            return;
        }
        String sessionUserAttributePath = ServletPluginProperties.sessionUserAttributePath();
        if (!sessionUserAttributePath.isEmpty()) {
            // capture user now, don't use a lazy supplier
            if (sessionUserAttributePath.equals(name)) {
                pluginServices.setTransactionUser(value.toString());
            } else if (sessionUserAttributePath.startsWith(name + ".")) {
                String user = HttpSessions.getSessionAttributeTextValue(session,
                        sessionUserAttributePath, sessionInvoker);
                if (user != null) {
                    // if user is null, don't clear it by setting Suppliers.ofInstance(null)
                    pluginServices.setTransactionUser(user);
                }
            }
        }
    }

    private static void updateSessionAttributesIfApplicable(ServletMessageSupplier messageSupplier,
            String name, @Nullable Object value, Object session, SessionInvoker sessionInvoker) {
        if (ServletPluginProperties.captureSessionAttributeNames().contains(name)
                || ServletPluginProperties.captureSessionAttributeNames().contains("*")) {
            // update all session attributes (possibly nested) at or under the set attribute
            for (String capturePath : ServletPluginProperties.captureSessionAttributePaths()) {
                if (capturePath.equals(name) || capturePath.equals("*")) {
                    updateSessionAttribute(messageSupplier, name, value);
                } else if (capturePath.startsWith(name + ".")) {
                    updateNestedSessionAttributes(messageSupplier, capturePath, value,
                            session, sessionInvoker);
                }
            }
        }
    }

    private static void updateSessionAttribute(ServletMessageSupplier messageSupplier, String name,
            @Nullable Object value) {
        if (value == null) {
            messageSupplier.putSessionAttributeChangedValue(name, null);
        } else {
            messageSupplier.putSessionAttributeChangedValue(name, value.toString());
        }
    }

    private static void updateNestedSessionAttributes(ServletMessageSupplier messageSupplier,
            String capturePath, @Nullable Object value, Object session,
            SessionInvoker sessionInvoker) {
        if (capturePath.endsWith(".*")) {
            String capturePathBase = capturePath.substring(0, capturePath.length() - 2);
            Object val = HttpSessions.getSessionAttribute(session, capturePathBase, sessionInvoker);
            if (val == null) {
                messageSupplier.putSessionAttributeChangedValue(capturePathBase, null);
            } else {
                for (Entry<String, String> entry : Beans.propertiesAsText(val).entrySet()) {
                    messageSupplier.putSessionAttributeChangedValue(
                            capturePathBase + "." + entry.getKey(), entry.getValue());
                }
            }
        } else if (value == null) {
            // no need to navigate path since it will always be null
            messageSupplier.putSessionAttributeChangedValue(capturePath, null);
        } else {
            String val = HttpSessions.getSessionAttributeTextValue(session, capturePath,
                    sessionInvoker);
            messageSupplier.putSessionAttributeChangedValue(capturePath, val);
        }
    }
}
