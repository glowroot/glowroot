/*
 * Copyright 2014-2023 the original author or authors.
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
package org.glowroot.agent.plugin.jakartaservlet;

import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.*;
import org.glowroot.agent.plugin.jakartaservlet.bclglowrootbcl.ResponseInvoker;
import org.glowroot.agent.plugin.jakartaservlet.bclglowrootbcl.ServletMessageSupplier;
import org.glowroot.agent.plugin.jakartaservlet.bclglowrootbcl.ServletPluginProperties;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class ResponseHeaderAspect {

    @Pointcut(className = "jakarta.servlet.ServletResponse", methodName = "setContentLength",
            methodParameterTypes = {"int"}, nestingGroup = "servlet-inner-call")
    public static class SetContentLengthAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            // good to short-cut advice if no response headers need to be captured
            return ServletPluginProperties.captureResponseHeadersNonEmpty();
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter int value) {
            if (!ServletPluginProperties.captureContentLengthResponseHeader()) {
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                messageSupplier.setResponseIntHeader("Content-Length", value);
            }
        }
    }

    @Pointcut(className = "jakarta.servlet.ServletResponse", methodName = "setContentLengthLong",
            methodParameterTypes = {"long"}, nestingGroup = "servlet-inner-call")
    public static class SetContentLengthLongAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            // good to short-cut advice if no response headers need to be captured
            return ServletPluginProperties.captureResponseHeadersNonEmpty();
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter long value) {
            if (!ServletPluginProperties.captureContentLengthResponseHeader()) {
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                messageSupplier.setResponseLongHeader("Content-Length", value);
            }
        }
    }

    @Pointcut(className = "jakarta.servlet.ServletResponse", methodName = "setContentType",
            methodParameterTypes = {"java.lang.String"}, nestingGroup = "servlet-inner-call")
    public static class SetContentTypeAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            // good to short-cut advice if no response headers need to be captured
            return ServletPluginProperties.captureResponseHeadersNonEmpty();
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindReceiver Object response,
                @BindParameter @Nullable String value,
                @BindClassMeta ResponseInvoker responseInvoker) {
            if (value == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!ServletPluginProperties.captureContentTypeResponseHeader()) {
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
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

    @Pointcut(className = "jakarta.servlet.ServletResponse", methodName = "setCharacterEncoding",
            methodParameterTypes = {"java.lang.String"}, nestingGroup = "servlet-inner-call")
    public static class SetCharacterEncodingAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            // good to short-cut advice if no response headers need to be captured
            return ServletPluginProperties.captureResponseHeadersNonEmpty();
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindReceiver Object response,
                @BindClassMeta ResponseInvoker responseInvoker) {
            if (!ServletPluginProperties.captureContentTypeResponseHeader()) {
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null && responseInvoker.hasGetContentTypeMethod()) {
                String contentType = responseInvoker.getContentType(response);
                messageSupplier.setResponseHeader("Content-Type", contentType);
            }
        }
    }

    @Pointcut(className = "jakarta.servlet.ServletResponse", methodName = "setLocale",
            methodParameterTypes = {"java.util.Locale"}, nestingGroup = "servlet-inner-call")
    public static class SetLocaleAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            // good to short-cut advice if no response headers need to be captured
            return ServletPluginProperties.captureResponseHeadersNonEmpty();
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindReceiver Object response,
                @BindParameter @Nullable Locale locale,
                @BindClassMeta ResponseInvoker responseInvoker) {
            if (locale == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            boolean captureContentLanguage =
                    ServletPluginProperties.captureContentLanguageResponseHeader();
            boolean captureContentType = ServletPluginProperties.captureContentTypeResponseHeader();
            if (!captureContentLanguage && !captureContentType) {
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
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

    @Pointcut(className = "jakarta.servlet.http.HttpServletResponse", methodName = "setHeader",
            methodParameterTypes = {"java.lang.String", "java.lang.String"},
            nestingGroup = "servlet-inner-call")
    public static class SetHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            // good to short-cut advice if no response headers need to be captured
            return ServletPluginProperties.captureResponseHeadersNonEmpty();
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter @Nullable String name,
                @BindParameter @Nullable String value) {
            if (name == null || value == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                messageSupplier.setResponseHeader(name, value);
            }
        }
    }

    @Pointcut(className = "jakarta.servlet.http.HttpServletResponse", methodName = "setDateHeader",
            methodParameterTypes = {"java.lang.String", "long"},
            nestingGroup = "servlet-inner-call")
    public static class SetDateHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            // good to short-cut advice if no response headers need to be captured
            return ServletPluginProperties.captureResponseHeadersNonEmpty();
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter @Nullable String name,
                @BindParameter long value) {
            if (name == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                messageSupplier.setResponseDateHeader(name, value);
            }
        }
    }

    @Pointcut(className = "jakarta.servlet.http.HttpServletResponse", methodName = "setIntHeader",
            methodParameterTypes = {"java.lang.String", "int"}, nestingGroup = "servlet-inner-call")
    public static class SetIntHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            // good to short-cut advice if no response headers need to be captured
            return ServletPluginProperties.captureResponseHeadersNonEmpty();
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter @Nullable String name,
                @BindParameter int value) {
            if (name == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                messageSupplier.setResponseIntHeader(name, value);
            }
        }
    }

    @Pointcut(className = "jakarta.servlet.http.HttpServletResponse", methodName = "addHeader",
            methodParameterTypes = {"java.lang.String", "java.lang.String"},
            nestingGroup = "servlet-inner-call")
    public static class AddHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            // good to short-cut advice if no response headers need to be captured
            return ServletPluginProperties.captureResponseHeadersNonEmpty();
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter @Nullable String name,
                @BindParameter @Nullable String value) {
            if (name == null || value == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                messageSupplier.addResponseHeader(name, value);
            }
        }
    }

    @Pointcut(className = "jakarta.servlet.http.HttpServletResponse", methodName = "addDateHeader",
            methodParameterTypes = {"java.lang.String", "long"},
            nestingGroup = "servlet-inner-call")
    public static class AddDateHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            // good to short-cut advice if no response headers need to be captured
            return ServletPluginProperties.captureResponseHeadersNonEmpty();
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter @Nullable String name,
                @BindParameter long value) {
            if (name == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                messageSupplier.addResponseDateHeader(name, value);
            }
        }
    }

    @Pointcut(className = "jakarta.servlet.http.HttpServletResponse", methodName = "addIntHeader",
            methodParameterTypes = {"java.lang.String", "int"}, nestingGroup = "servlet-inner-call")
    public static class AddIntHeaderAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            // good to short-cut advice if no response headers need to be captured
            return ServletPluginProperties.captureResponseHeadersNonEmpty();
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter @Nullable String name,
                @BindParameter int value) {
            if (name == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            if (!captureResponseHeader(name)) {
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                messageSupplier.addResponseIntHeader(name, value);
            }
        }
    }

    private static boolean captureResponseHeader(String name) {
        List<Pattern> capturePatterns = ServletPluginProperties.captureResponseHeaders();
        // converted to lower case for case-insensitive matching (patterns are lower case)
        String keyLowerCase = name.toLowerCase(Locale.ENGLISH);
        return DetailCapture.matchesOneOf(keyLowerCase, capturePatterns);
    }
}
