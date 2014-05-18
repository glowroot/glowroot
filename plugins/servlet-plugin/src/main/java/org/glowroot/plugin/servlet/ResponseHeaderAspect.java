/*
 * Copyright 2014 the original author or authors.
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

import java.util.Locale;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;

import org.glowroot.api.PluginServices;
import org.glowroot.api.UnresolvedMethod;
import org.glowroot.api.weaving.BindMethodArg;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ResponseHeaderAspect {

    private static final PluginServices pluginServices = PluginServices.get("servlet");

    // this method only exists since Servlet 2.4 (e.g. since Tomcat 5.5.x)
    private static final UnresolvedMethod getContentTypeMethod =
            UnresolvedMethod.from("javax.servlet.ServletResponse", "getContentType");

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private static final ThreadLocal<Boolean> inAdvice = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    @Pointcut(type = "javax.servlet.ServletResponse", methodName = "setContentLength",
            methodArgTypes = {"int"}, ignoreSameNested = true)
    public static class SetContentLengthAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return isEnabledCommon();
        }
        @OnBefore
        public static void onBefore() {
            inAdvice.set(true);
        }
        @OnAfter
        public static void onAfter(@BindMethodArg int value) {
            inAdvice.set(false);
            if (!captureResponseHeader("Content-Length")) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.setResponseIntHeader("Content-Length", value);
            }
        }
    }

    @Pointcut(type = "javax.servlet.ServletResponse", methodName = "setContentType",
            methodArgTypes = {"java.lang.String"}, ignoreSameNested = true)
    public static class SetContentTypeAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return isEnabledCommon();
        }
        @OnBefore
        public static void onBefore() {
            inAdvice.set(true);
        }
        @OnAfter
        public static void onAfter(@BindReceiver Object response, @BindMethodArg String value) {
            inAdvice.set(false);
            if (!captureResponseHeader("Content-Type")) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                String contentType = (String) getContentTypeMethod.invoke(response, null);
                if (contentType == null) {
                    // Servlet 2.3 or prior
                    messageSupplier.setResponseHeader("Content-Type", value);
                } else {
                    // Servlet 2.4 or later
                    messageSupplier.setResponseHeader("Content-Type", contentType);
                }
            }
        }
    }

    @Pointcut(type = "javax.servlet.ServletResponse", methodName = "setCharacterEncoding",
            methodArgTypes = {"java.lang.String"}, ignoreSameNested = true)
    public static class SetCharacterEncodingAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return isEnabledCommon();
        }
        @OnBefore
        public static void onBefore() {
            inAdvice.set(true);
        }
        @OnAfter
        public static void onAfter(@BindReceiver Object response) {
            inAdvice.set(false);
            if (!captureResponseHeader("Content-Type")) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                String contentType = (String) getContentTypeMethod.invoke(response, null);
                if (contentType != null) {
                    // Servlet 2.4 or later
                    messageSupplier.setResponseHeader("Content-Type", contentType);
                }
            }
        }
    }

    @Pointcut(type = "javax.servlet.ServletResponse", methodName = "setLocale",
            methodArgTypes = {"java.util.Locale"}, ignoreSameNested = true)
    public static class SetLocaleAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return isEnabledCommon();
        }
        @OnBefore
        public static void onBefore() {
            inAdvice.set(true);
        }
        @OnAfter
        public static void onAfter(@BindReceiver Object response, @BindMethodArg Locale locale) {
            inAdvice.set(false);
            boolean captureContentLanguage = captureResponseHeader("Content-Language");
            boolean captureContentType = captureResponseHeader("Content-Type");
            if (!captureContentLanguage && !captureContentType) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                if (captureContentLanguage) {
                    messageSupplier.setResponseHeader("Content-Language", locale.toString());
                }
                if (captureContentType) {
                    String contentType = (String) getContentTypeMethod.invoke(response, null);
                    if (contentType != null) {
                        // Servlet 2.4 or later
                        messageSupplier.setResponseHeader("Content-Type", contentType);
                    }
                }
            }
        }
    }

    @Pointcut(type = "javax.servlet.http.HttpServletResponse", methodName = "setHeader",
            methodArgTypes = {"java.lang.String", "java.lang.String"}, ignoreSameNested = true)
    public static class SetHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return isEnabledCommon();
        }
        @OnBefore
        public static void onBefore() {
            inAdvice.set(true);
        }
        @OnAfter
        public static void onAfter(@BindMethodArg String name, @BindMethodArg String value) {
            inAdvice.set(false);
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.setResponseHeader(name, value);
            }
        }
    }

    @Pointcut(type = "javax.servlet.http.HttpServletResponse", methodName = "setDateHeader",
            methodArgTypes = {"java.lang.String", "long"}, ignoreSameNested = true)
    public static class SetDateHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return isEnabledCommon();
        }
        @OnBefore
        public static void onBefore() {
            inAdvice.set(true);
        }
        @OnAfter
        public static void onAfter(@BindMethodArg String name, @BindMethodArg long value) {
            inAdvice.set(false);
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.setResponseDateHeader(name, value);
            }
        }
    }

    @Pointcut(type = "javax.servlet.http.HttpServletResponse", methodName = "setIntHeader",
            methodArgTypes = {"java.lang.String", "int"}, ignoreSameNested = true)
    public static class SetIntHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return isEnabledCommon();
        }
        @OnBefore
        public static void onBefore() {
            inAdvice.set(true);
        }
        @OnAfter
        public static void onAfter(@BindMethodArg String name, @BindMethodArg int value) {
            inAdvice.set(false);
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.setResponseIntHeader(name, value);
            }
        }
    }

    @Pointcut(type = "javax.servlet.http.HttpServletResponse", methodName = "addHeader",
            methodArgTypes = {"java.lang.String", "java.lang.String"}, ignoreSameNested = true)
    public static class AddHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return isEnabledCommon();
        }
        @OnBefore
        public static void onBefore() {
            inAdvice.set(true);
        }
        @OnAfter
        public static void onAfter(@BindMethodArg String name, @BindMethodArg String value) {
            inAdvice.set(false);
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.addResponseHeader(name, value);
            }
        }
    }

    @Pointcut(type = "javax.servlet.http.HttpServletResponse", methodName = "addDateHeader",
            methodArgTypes = {"java.lang.String", "long"}, ignoreSameNested = true)
    public static class AddDateHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return isEnabledCommon();
        }
        @OnBefore
        public static void onBefore() {
            inAdvice.set(true);
        }
        @OnAfter
        public static void onAfter(@BindMethodArg String name, @BindMethodArg long value) {
            inAdvice.set(false);
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.addResponseDateHeader(name, value);
            }
        }
    }

    @Pointcut(type = "javax.servlet.http.HttpServletResponse", methodName = "addIntHeader",
            methodArgTypes = {"java.lang.String", "int"}, ignoreSameNested = true)
    public static class AddIntHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return isEnabledCommon();
        }
        @OnBefore
        public static void onBefore() {
            inAdvice.set(true);
        }
        @OnAfter
        public static void onAfter(@BindMethodArg String name, @BindMethodArg int value) {
            inAdvice.set(false);
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.addResponseIntHeader(name, value);
            }
        }
    }

    private static boolean isEnabledCommon() {
        // good to short-cut advice if no response headers need to be captured
        return pluginServices.isEnabled()
                && !ServletPluginProperties.captureResponseHeaders().isEmpty() && !inAdvice.get();
    }

    private static boolean captureResponseHeader(String name) {
        ImmutableList<Pattern> capturePatterns = ServletPluginProperties.captureResponseHeaders();
        // converted to lower case for case-insensitive matching (patterns are lower case)
        String keyLowerCase = name.toLowerCase(Locale.ENGLISH);
        return DetailCapture.matchesOneOf(keyLowerCase, capturePatterns);
    }
}
