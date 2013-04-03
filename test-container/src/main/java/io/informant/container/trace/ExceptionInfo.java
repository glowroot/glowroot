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

import java.util.List;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ExceptionInfo {

    @Nullable
    private String display;
    @Nullable
    private List<String> stackTrace;
    private int framesInCommonWithCaused;
    @Nullable
    private ExceptionInfo cause;

    @Nullable
    public String getDisplay() {
        return display;
    }

    public void setDisplay(String display) {
        this.display = display;
    }

    @Nullable
    public List<String> getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(List<String> stackTrace) {
        this.stackTrace = stackTrace;
    }

    public int getFramesInCommonWithCaused() {
        return framesInCommonWithCaused;
    }

    public void setFramesInCommonWithCaused(int framesInCommonWithCaused) {
        this.framesInCommonWithCaused = framesInCommonWithCaused;
    }

    @Nullable
    public ExceptionInfo getCause() {
        return cause;
    }

    public void setCause(@Nullable ExceptionInfo cause) {
        this.cause = cause;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("display", display)
                .add("stackTrace", stackTrace)
                .add("framesInCommonWithCaused", framesInCommonWithCaused)
                .add("cause", cause)
                .toString();
    }
}
