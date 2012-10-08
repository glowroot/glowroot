/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.api;

import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class ErrorMessage {

    public abstract String getText();

    @Nullable
    public abstract Map<String, ?> getDetail();

    @Nullable
    public abstract CapturedException getException();

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
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    @Immutable
    private static class ErrorMessageImpl extends ErrorMessage {

        private final String text;
        @Nullable
        private final Map<String, ?> detail;
        @Nullable
        private final CapturedException exception;

        private ErrorMessageImpl(String text, @Nullable Map<String, ?> detail,
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
        @Nullable
        public Map<String, ?> getDetail() {
            return detail;
        }

        @Override
        @Nullable
        public CapturedException getException() {
            return exception;
        }
    }
}
