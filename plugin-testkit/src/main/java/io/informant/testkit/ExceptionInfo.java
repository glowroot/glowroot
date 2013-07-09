/*
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
package io.informant.testkit;

import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import static io.informant.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class ExceptionInfo {

    private final String display;
    private final ImmutableList<String> stackTrace;
    private final int framesInCommonWithCaused;
    @Nullable
    private final ExceptionInfo cause;

    private ExceptionInfo(String display, @ReadOnly List<String> stackTrace,
            int framesInCommonWithCaused, @Nullable ExceptionInfo cause) {
        this.display = display;
        this.stackTrace = ImmutableList.copyOf(stackTrace);
        this.framesInCommonWithCaused = framesInCommonWithCaused;
        this.cause = cause;
    }

    public String getDisplay() {
        return display;
    }

    public ImmutableList<String> getStackTrace() {
        return stackTrace;
    }

    public int getFramesInCommonWithCaused() {
        return framesInCommonWithCaused;
    }

    @Nullable
    public ExceptionInfo getCause() {
        return cause;
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

    @JsonCreator
    static ExceptionInfo readValue(
            @JsonProperty("display") @Nullable String display,
            @JsonProperty("stackTrace") @Nullable List<String> stackTrace,
            @JsonProperty("framesInCommonWithCaused") @Nullable Integer framesInCommonWithCaused,
            @JsonProperty("cause") @Nullable ExceptionInfo cause)
            throws JsonMappingException {
        checkRequiredProperty(display, "display");
        checkRequiredProperty(stackTrace, "stackTrace");
        checkRequiredProperty(framesInCommonWithCaused, "framesInCommonWithCaused");
        return new ExceptionInfo(display, stackTrace, framesInCommonWithCaused, cause);
    }
}
