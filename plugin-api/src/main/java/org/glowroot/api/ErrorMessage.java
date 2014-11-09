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
package org.glowroot.api;

import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.internal.ExceptionInfo;
import org.glowroot.api.internal.ReadableErrorMessage;

/**
 * The detail map can contain only {@link String}, {@link Double}, {@link Boolean} and null value
 * types. It can also contain nested maps (which have the same restrictions on value types,
 * including additional levels of nested maps). The detail map cannot have null keys.
 * 
 * As an extra bonus, detail map can also contain org.glowroot.api.Optional values which is useful
 * for Maps that do not accept null values, e.g. java.util.concurrent.ConcurrentHashMap and
 * org.glowroot.shaded.google.common.collect.ImmutableMap.
 * 
 * The detail map does not need to be thread safe as long as it is only instantiated in response to
 * either MessageSupplier.get() or Message.getDetail() which are called by the thread that needs the
 * map.
 */
public abstract class ErrorMessage {

    private static final Logger logger = LoggerFactory.getLogger(ErrorMessage.class);

    public static ErrorMessage from(Throwable t) {
        if (t == null) {
            logger.warn("from(): argument 't' must be non-null");
            return from((String) null);
        }
        return new ErrorMessageImpl(getRootCause(t).toString(), ExceptionInfo.from(t), null);
    }

    // accepts null message so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage from(@Nullable String message, Throwable t) {
        if (t == null) {
            logger.warn("from(): argument 't' must be non-null");
            return from(message);
        }
        return new ErrorMessageImpl(message, ExceptionInfo.from(t), null);
    }

    // accepts null message so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage from(@Nullable String message) {
        return new ErrorMessageImpl(message, null, null);
    }

    public static ErrorMessage withDetail(Throwable t,
            Map<String, ? extends /*@Nullable*/Object> detail) {
        if (t == null) {
            logger.warn("withDetail(): argument 't' must be non-null");
            return withDetail((String) null, detail);
        }
        return new ErrorMessageImpl(getRootCause(t).toString(), ExceptionInfo.from(t), detail);
    }

    // accepts null message so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage withDetail(@Nullable String message, Throwable t,
            Map<String, ? extends /*@Nullable*/Object> detail) {
        if (t == null) {
            logger.warn("withDetail(): argument 't' must be non-null");
            return withDetail(message, detail);
        }
        return new ErrorMessageImpl(message, ExceptionInfo.from(t), detail);
    }

    // accepts null message so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage withDetail(@Nullable String message,
            Map<String, ? extends /*@Nullable*/Object> detail) {
        if (detail == null) {
            logger.warn("withDetail(): argument 'detail' must be non-null");
            return new ErrorMessageImpl(message, null, null);
        }
        return new ErrorMessageImpl(message, null, detail);
    }

    private ErrorMessage() {}

    private static Throwable getRootCause(Throwable t) {
        Throwable root = t;
        Throwable cause = root.getCause();
        while (cause != null) {
            root = cause;
            cause = root.getCause();
        }
        return root;
    }

    // implementing ReadableErrorMessage is just a way to access this class from glowroot without
    // making it (obviously) accessible to plugin implementations
    private static class ErrorMessageImpl extends ErrorMessage implements ReadableErrorMessage {

        @Nullable
        private final String text;
        @Nullable
        private final ExceptionInfo exceptionInfo;
        @Nullable
        private final Map<String, ? extends /*@Nullable*/Object> detail;

        private ErrorMessageImpl(@Nullable String text, @Nullable ExceptionInfo exceptionInfo,
                @Nullable Map<String, ? extends /*@Nullable*/Object> detail) {
            this.text = text;
            this.exceptionInfo = exceptionInfo;
            this.detail = detail;
        }

        @Override
        public String getText() {
            return Strings.nullToEmpty(text);
        }

        @Override
        @Nullable
        public ExceptionInfo getExceptionInfo() {
            return exceptionInfo;
        }

        @Override
        @Nullable
        public Map<String, ? extends /*@Nullable*/Object> getDetail() {
            return detail;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("text", text)
                    .add("exception", exceptionInfo)
                    .add("detail", detail)
                    .toString();
        }
    }
}
