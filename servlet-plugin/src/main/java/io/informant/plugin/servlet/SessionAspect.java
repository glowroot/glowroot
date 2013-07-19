/*
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.plugin.servlet;

import java.util.Map.Entry;

import checkers.nullness.quals.Nullable;

import io.informant.api.Beans;
import io.informant.api.PluginServices;
import io.informant.api.weaving.BindMethodArg;
import io.informant.api.weaving.BindReturn;
import io.informant.api.weaving.BindTarget;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SessionAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("io.informant.plugins:servlet-plugin");

    /*
     * ================== Http Session Attributes ==================
     */

    @Pointcut(typeName = "javax.servlet.http.HttpServletRequest", methodName = "getSession",
            methodArgs = {".."})
    public static class GetSessionAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object realSession) {
            if (realSession == null) {
                return;
            }
            HttpSession session = HttpSession.from(realSession);
            // either getSession(), getSession(true) or getSession(false) has triggered this
            // pointcut
            // after calls to the first two (no-arg, and passing true), a new session may have been
            // created (the third one -- passing false -- could be ignored but is harmless)
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null && session.isNew()) {
                messageSupplier.setSessionIdUpdatedValue(session.getId());
            }
        }
    }

    @Pointcut(typeName = "javax.servlet.http.HttpSession", methodName = "invalidate")
    public static class InvalidateAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static void onBefore(@BindTarget Object realSession) {
            HttpSession session = HttpSession.from(realSession);
            ServletMessageSupplier messageSupplier = getServletMessageSupplier(session);
            if (messageSupplier != null) {
                messageSupplier.setSessionIdUpdatedValue("");
            }
        }
    }

    @Pointcut(typeName = "javax.servlet.http.HttpSession", methodName = "setAttribute|putValue",
            methodArgs = {"java.lang.String", "java.lang.Object"})
    public static class SetAttributeAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnAfter
        public static void onAfter(@BindTarget Object realSession,
                @BindMethodArg @Nullable String name,
                @BindMethodArg @Nullable Object value) {
            if (name == null) {
                // theoretically possible, so just ignore
                return;
            }
            HttpSession session = HttpSession.from(realSession);
            // name is non-null per HttpSession.setAttribute() javadoc, but value may be null
            // (which per the javadoc is the same as calling removeAttribute())
            ServletMessageSupplier messageSupplier = getServletMessageSupplier(session);
            if (messageSupplier != null) {
                updateUserIdIfApplicable(name, value, session);
                updateSessionAttributesIfApplicable(messageSupplier, name, value, session);
            }
        }
    }

    @Pointcut(typeName = "javax.servlet.http.HttpSession", methodName = "removeAttribute",
            methodArgs = {"java.lang.String"})
    public static class RemoveAttributeAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnAfter
        public static void onAfter(@BindTarget Object realSession,
                @BindMethodArg @Nullable String name) {
            // calling HttpSession.setAttribute() with null value is the same as calling
            // removeAttribute(), per the setAttribute() javadoc
            SetAttributeAdvice.onAfter(realSession, name, null);
        }
    }

    @Nullable
    private static ServletMessageSupplier getServletMessageSupplier(HttpSession session) {
        ServletMessageSupplier servletMessageSupplier = ServletAspect.getServletMessageSupplier();
        if (servletMessageSupplier == null) {
            // this thread is not executing a servlet request, e.g. this could be a background
            // thread that is updating http session attributes
            return null;
        }
        String sessionId;
        if (servletMessageSupplier.getSessionIdUpdatedValue() != null) {
            sessionId = servletMessageSupplier.getSessionIdUpdatedValue();
        } else {
            sessionId = servletMessageSupplier.getSessionIdInitialValue();
        }
        if (session.getId().equals(sessionId)) {
            return servletMessageSupplier;
        } else {
            // the target session for this pointcut is not the same as the thread's
            // ServletMessageSupplier, e.g. this could be a request that is updating attributes on
            // a different http session
            return null;
        }
    }

    private static void updateUserIdIfApplicable(String name, @Nullable Object value,
            HttpSession session) {
        if (value == null) {
            // if user id value is set to null, don't clear it
            return;
        }
        String sessionUserIdAttributePath = ServletPluginProperties.sessionUserIdAttributePath();
        if (!sessionUserIdAttributePath.equals("")) {
            // capture user id now, don't use a lazy supplier
            if (sessionUserIdAttributePath.equals(name)) {
                pluginServices.setUserId(value.toString());
            } else if (sessionUserIdAttributePath.startsWith(name + ".")) {
                String userId = session.getSessionAttributeTextValue(sessionUserIdAttributePath);
                if (userId != null) {
                    // if user id is null, don't clear it by setting Suppliers.ofInstance(null)
                    pluginServices.setUserId(userId);
                }
            }
        }
    }

    private static void updateSessionAttributesIfApplicable(ServletMessageSupplier messageSupplier,
            String name, @Nullable Object value, HttpSession session) {
        if (ServletPluginProperties.captureSessionAttributeNames().contains(name)
                || ServletPluginProperties.captureSessionAttributeNames().contains("*")) {
            // update all session attributes (possibly nested) at or under the set attribute
            for (String capturePath : ServletPluginProperties.captureSessionAttributePaths()) {
                if (capturePath.equals(name) || capturePath.equals("*")) {
                    updateSessionAttribute(messageSupplier, name, value);
                } else if (capturePath.startsWith(name + ".")) {
                    updateNestedSessionAttributes(messageSupplier, capturePath, value,
                            session);
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
            String capturePath, @Nullable Object value, HttpSession session) {
        if (capturePath.endsWith(".*")) {
            String capturePathBase = capturePath.substring(0, capturePath.length() - 2);
            Object val = session.getSessionAttribute(capturePathBase);
            if (val == null) {
                messageSupplier.putSessionAttributeChangedValue(capturePathBase, null);
            } else {
                for (Entry<String, String> entry : Beans.propertiesAsText(val)
                        .entrySet()) {
                    messageSupplier.putSessionAttributeChangedValue(
                            capturePathBase + "." + entry.getKey(), entry.getValue());
                }
            }
        } else if (value == null) {
            // no need to navigate path since it will always be null
            messageSupplier.putSessionAttributeChangedValue(capturePath, null);
        } else {
            String val = session.getSessionAttributeTextValue(capturePath);
            messageSupplier.putSessionAttributeChangedValue(capturePath, val);
        }
    }
}
