/**
 * Copyright 2013 the original author or authors.
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
package io.informant.container.trace;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ErrorMessage extends Message {

    @Nullable
    private ExceptionInfo exception;

    @Nullable
    public ExceptionInfo getException() {
        return exception;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("text", getText())
                .add("detail", getDetail())
                .add("exception", exception)
                .toString();
    }
}
