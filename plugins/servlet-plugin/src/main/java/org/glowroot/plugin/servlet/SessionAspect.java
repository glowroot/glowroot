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

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.api.PluginServices;
import org.glowroot.api.weaving.BindClassMeta;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SessionAspect {

    private static final PluginServices pluginServices = PluginServices.get("servlet");

    /*
     * ================== Http Session Attributes ==================
     */

    @Pointcut(className = "javax.servlet.http.HttpServletRequest", methodName = "getSession",
            methodParameterTypes = {".."})
    public static class GetSessionAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object session,
                @BindClassMeta SessionInvoker sessionInvoker) {
            if (session == null) {
                return;
            }
            // either getSession(), getSession(true) or getSession(false) has triggered this
            // pointcut
            // after calls to the first two (no-arg, and passing true), a new session may have been
            // created (the third one -- passing false -- could be ignored but is harmless)
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null && sessionInvoker.isNew(session)) {
                messageSupplier.setSessionIdUpdatedValue(sessionInvoker.getId(session));
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpSession", methodName = "invalidate")
    public static class InvalidateAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static void onBefore(@BindReceiver Object session,
                @BindClassMeta SessionInvoker sessionInvoker) {
            String sessionId = sessionInvoker.getId(session);
            ServletMessageSupplier messageSupplier = getServletMessageSupplier(sessionId);
            if (messageSupplier != null) {
                messageSupplier.setSessionIdUpdatedValue("");
            }
        }
    }

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
            String sessionId = sessionInvoker.getId(session);
            ServletMessageSupplier messageSupplier = getServletMessageSupplier(sessionId);
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

    @Nullable
    private static ServletMessageSupplier getServletMessageSupplier(String sessionId) {
        ServletMessageSupplier servletMessageSupplier = ServletAspect.getServletMessageSupplier();
        if (servletMessageSupplier == null) {
            // this thread is not executing a servlet request, e.g. this could be a background
            // thread that is updating http session attributes
            return null;
        }
        String supplierSessionId;
        if (servletMessageSupplier.getSessionIdUpdatedValue() != null) {
            supplierSessionId = servletMessageSupplier.getSessionIdUpdatedValue();
        } else {
            supplierSessionId = servletMessageSupplier.getSessionIdInitialValue();
        }
        if (sessionId.equals(supplierSessionId)) {
            return servletMessageSupplier;
        } else {
            // the target session for this pointcut is not the same as the thread's
            // ServletMessageSupplier, e.g. this could be a request that is updating attributes on
            // a different http session
            return null;
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
