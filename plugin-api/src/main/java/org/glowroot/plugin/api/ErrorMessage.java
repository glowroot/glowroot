/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.plugin.api;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.plugin.api.internal.ReadableErrorMessage;
import org.glowroot.plugin.api.internal.ThrowableInfo;

/**
 * @see TraceEntry#endWithError(ErrorMessage)
 * @see PluginServices#addTraceEntry(ErrorMessage)
 */
public abstract class ErrorMessage {

    private static final Logger logger = LoggerFactory.getLogger(ErrorMessage.class);

    public static ErrorMessage from(Throwable t) {
        if (t == null) {
            logger.warn("from(): argument 't' must be non-null");
            return from((String) null);
        }
        return new ErrorMessageImpl(getRootCause(t).toString(), ThrowableInfo.from(t));
    }

    // accepts null values so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage from(@Nullable String message, @Nullable Throwable t) {
        if (t == null) {
            return from(message);
        }
        return new ErrorMessageImpl(message, ThrowableInfo.from(t));
    }

    // accepts null message so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage from(@Nullable String message) {
        return new ErrorMessageImpl(message, null);
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
    // making it accessible to plugins
    private static class ErrorMessageImpl extends ErrorMessage implements ReadableErrorMessage {

        private final @Nullable String message;
        private final @Nullable ThrowableInfo throwableInfo;

        private ErrorMessageImpl(@Nullable String text, @Nullable ThrowableInfo throwableInfo) {
            this.message = text;
            this.throwableInfo = throwableInfo;
        }

        @Override
        public String getMessage() {
            return Strings.nullToEmpty(message);
        }

        @Override
        public @Nullable ThrowableInfo getThrowable() {
            return throwableInfo;
        }
    }
}
