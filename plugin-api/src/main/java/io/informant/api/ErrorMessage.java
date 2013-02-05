/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.api;

import java.util.Map;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;

/**
 * The detail map can contain only {@link String}, {@link Double}, {@link Boolean} and null value
 * types. It can also contain nested maps (which have the same restrictions on value types,
 * including additional levels of nested maps). The detail map cannot have null keys.
 * 
 * As an extra bonus, detail map can also contain io.informant.api.Optional values which is useful
 * for Maps that do not accept null values, e.g.
 * io.informant.shaded.google.common.collect.ImmutableMap.
 * 
 * The detail map does not need to be thread safe as long as it is only instantiated in response to
 * either MessageSupplier.get() or Message.getDetail() which are called by the thread that needs the
 * map.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class ErrorMessage {

    public abstract String getText();

    @ReadOnly
    @Nullable
    public abstract Map<String, ? extends /*@Nullable*/Object> getDetail();

    @Nullable
    public abstract CapturedException getException();

    // keep constructor protected, if plugins could subclass, then extra validation would be
    // required to ensure the Message contract is maintained
    protected ErrorMessage() {}

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("text", getText())
                .add("detail", getDetail())
                .add("exception", getException())
                .toString();
    }

    public static ErrorMessage from(Throwable t) {
        return new ErrorMessageImpl(getRootCause(t).toString(), null, CapturedException.from(t));
    }

    public static ErrorMessage from(String message, Throwable t) {
        return new ErrorMessageImpl(message, null, CapturedException.from(t));
    }

    private static Throwable getRootCause(Throwable t) {
        Throwable root = t;
        Throwable cause = root.getCause();
        while (cause != null) {
            root = cause;
            cause = root.getCause();
        }
        return root;
    }

    private static class ErrorMessageImpl extends ErrorMessage {

        private final String text;
        @ReadOnly
        @Nullable
        private final Map<String, ? extends /*@Nullable*/Object> detail;
        @Nullable
        private final CapturedException exception;

        private ErrorMessageImpl(String text,
                @ReadOnly @Nullable Map<String, ? extends /*@Nullable*/Object> detail,
                @Nullable CapturedException exception) {
            this.text = text;
            this.detail = detail;
            this.exception = exception;
        }

        @Override
        public String getText() {
            return text;
        }

        @Override
        @ReadOnly
        @Nullable
        public Map<String, ? extends /*@Nullable*/Object> getDetail() {
            return detail;
        }

        @Override
        @Nullable
        public CapturedException getException() {
            return exception;
        }
    }
}
