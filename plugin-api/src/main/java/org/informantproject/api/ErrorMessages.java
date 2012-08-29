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

import com.google.common.base.Strings;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class ErrorMessages {

    public static ErrorMessage from(Throwable t) {
        String text = t.getMessage();
        if (Strings.isNullOrEmpty(text)) {
            text = t.getClass().getName();
        }
        return new ErrorMessageImpl(text, null, t.getStackTrace());
    }

    public static ErrorMessage from(String message, Throwable t) {
        return new ErrorMessageImpl(message, null, t.getStackTrace());
    }

    @Immutable
    private static class ErrorMessageImpl implements ErrorMessage {

        private final String text;
        @Nullable
        private final Map<String, ?> detail;
        @Nullable
        private final StackTraceElement[] stackTraceElements;

        private ErrorMessageImpl(String text, @Nullable Map<String, ?> detail,
                @Nullable StackTraceElement[] stackTraceElements) {

            this.text = text;
            this.detail = detail;
            this.stackTraceElements = stackTraceElements;
        }

        public String getText() {
            return text;
        }

        @Nullable
        public Map<String, ?> getDetail() {
            return detail;
        }

        @Nullable
        public StackTraceElement[] getStackTrace() {
            return stackTraceElements;
        }
    }

    private ErrorMessages() {}
}
