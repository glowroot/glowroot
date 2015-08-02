/*
 * Copyright 2014-2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import org.glowroot.plugin.api.FastThreadLocal;
import org.glowroot.plugin.api.PluginServices;
import org.glowroot.plugin.api.weaving.BindClassMeta;
import org.glowroot.plugin.api.weaving.BindParameter;
import org.glowroot.plugin.api.weaving.BindReceiver;
import org.glowroot.plugin.api.weaving.IsEnabled;
import org.glowroot.plugin.api.weaving.OnAfter;
import org.glowroot.plugin.api.weaving.OnBefore;
import org.glowroot.plugin.api.weaving.Pointcut;

public class ResponseHeaderAspect {

    private static final PluginServices pluginServices = PluginServices.get("servlet");

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private static final FastThreadLocal<Boolean> inAdvice = new FastThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    @Pointcut(className = "javax.servlet.ServletResponse", methodName = "setContentLength",
            methodParameterTypes = {"int"})
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
        public static void onAfter(@BindParameter int value) {
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

    @Pointcut(className = "javax.servlet.ServletResponse", methodName = "setContentLengthLong",
            methodParameterTypes = {"long"})
    public static class SetContentLengthLongAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return isEnabledCommon();
        }
        @OnBefore
        public static void onBefore() {
            inAdvice.set(true);
        }
        @OnAfter
        public static void onAfter(@BindParameter long value) {
            inAdvice.set(false);
            if (!captureResponseHeader("Content-Length")) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.setResponseLongHeader("Content-Length", value);
            }
        }
    }

    @Pointcut(className = "javax.servlet.ServletResponse", methodName = "setContentType",
            methodParameterTypes = {"java.lang.String"})
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
        public static void onAfter(@BindReceiver Object response,
                @BindParameter @Nullable String value,
                @BindClassMeta ResponseInvoker responseInvoker) {
            inAdvice.set(false);
            if (value == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader("Content-Type")) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                if (responseInvoker.hasGetContentTypeMethod()) {
                    String contentType = responseInvoker.getContentType(response);
                    messageSupplier.setResponseHeader("Content-Type", contentType);
                } else {
                    messageSupplier.setResponseHeader("Content-Type", value);
                }
            }
        }
    }

    @Pointcut(className = "javax.servlet.ServletResponse", methodName = "setCharacterEncoding",
            methodParameterTypes = {"java.lang.String"})
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
        public static void onAfter(@BindReceiver Object response,
                @BindClassMeta ResponseInvoker responseInvoker) {
            inAdvice.set(false);
            if (!captureResponseHeader("Content-Type")) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null && responseInvoker.hasGetContentTypeMethod()) {
                String contentType = responseInvoker.getContentType(response);
                messageSupplier.setResponseHeader("Content-Type", contentType);
            }
        }
    }

    @Pointcut(className = "javax.servlet.ServletResponse", methodName = "setLocale",
            methodParameterTypes = {"java.util.Locale"})
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
        public static void onAfter(@BindReceiver Object response,
                @BindParameter @Nullable Locale locale,
                @BindClassMeta ResponseInvoker responseInvoker) {
            inAdvice.set(false);
            if (locale == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
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
                if (captureContentType && responseInvoker.hasGetContentTypeMethod()) {
                    String contentType = responseInvoker.getContentType(response);
                    messageSupplier.setResponseHeader("Content-Type", contentType);
                }
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "setHeader",
            methodParameterTypes = {"java.lang.String", "java.lang.String"})
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
        public static void onAfter(@BindParameter @Nullable String name,
                @BindParameter @Nullable String value) {
            inAdvice.set(false);
            if (name == null || value == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.setResponseHeader(name, value);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "setDateHeader",
            methodParameterTypes = {"java.lang.String", "long"})
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
        public static void onAfter(@BindParameter @Nullable String name,
                @BindParameter long value) {
            inAdvice.set(false);
            if (name == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.setResponseDateHeader(name, value);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "setIntHeader",
            methodParameterTypes = {"java.lang.String", "int"})
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
        public static void onAfter(@BindParameter @Nullable String name, @BindParameter int value) {
            inAdvice.set(false);
            if (name == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.setResponseIntHeader(name, value);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "addHeader",
            methodParameterTypes = {"java.lang.String", "java.lang.String"})
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
        public static void onAfter(@BindParameter @Nullable String name,
                @BindParameter @Nullable String value) {
            inAdvice.set(false);
            if (name == null || value == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.addResponseHeader(name, value);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "addDateHeader",
            methodParameterTypes = {"java.lang.String", "long"})
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
        public static void onAfter(@BindParameter @Nullable String name,
                @BindParameter long value) {
            inAdvice.set(false);
            if (name == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null) {
                messageSupplier.addResponseDateHeader(name, value);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "addIntHeader",
            methodParameterTypes = {"java.lang.String", "int"})
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
        public static void onAfter(@BindParameter @Nullable String name, @BindParameter int value) {
            inAdvice.set(false);
            if (name == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
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
        return !ServletPluginProperties.captureResponseHeaders().isEmpty()
                && pluginServices.isEnabled() && !inAdvice.get();
    }

    private static boolean captureResponseHeader(String name) {
        ImmutableList<Pattern> capturePatterns = ServletPluginProperties.captureResponseHeaders();
        // converted to lower case for case-insensitive matching (patterns are lower case)
        String keyLowerCase = name.toLowerCase(Locale.ENGLISH);
        return DetailCapture.matchesOneOf(keyLowerCase, capturePatterns);
    }
}
