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

import java.util.Map;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;

import static io.informant.container.common.ObjectMappers.nullToEmpty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class TraceError {

    @Nullable
    private final String text;
    private final ImmutableMap<String, Object> detail;
    @Nullable
    private final ExceptionInfo exception;

    private TraceError(@Nullable String text, @ReadOnly Map<String, Object> detail,
            @Nullable ExceptionInfo exception) {
        this.text = text;
        this.detail = ImmutableMap.copyOf(detail);
        this.exception = exception;
    }

    @Nullable
    public String getText() {
        return text;
    }

    public ImmutableMap<String, Object> getDetail() {
        return detail;
    }

    @Nullable
    public ExceptionInfo getException() {
        return exception;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("text", text)
                .add("detail", detail)
                .add("exception", exception)
                .toString();
    }

    @JsonCreator
    static TraceError readValue(
            @JsonProperty("text") @Nullable String text,
            @JsonProperty("detail") @Nullable Map<String, Object> detail,
            @JsonProperty("exception") @Nullable ExceptionInfo exception)
            throws JsonMappingException {
        return new TraceError(text, nullToEmpty(detail), exception);
    }
}
