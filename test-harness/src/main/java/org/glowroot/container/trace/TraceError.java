/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.container.trace;

import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;

import static org.glowroot.container.common.ObjectMappers.nullToEmpty;
/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class TraceError {

    @Nullable
    private final String text;
    @Nullable
    private final ExceptionInfo exception;
    // can't use ImmutableMap since detail can have null values
    private final Map<String, /*@Nullable*/Object> detail;

    private TraceError(@Nullable String text, @Nullable ExceptionInfo exception,
            Map<String, /*@Nullable*/Object> detail) {
        this.text = text;
        this.exception = exception;
        this.detail = detail;
    }

    @Nullable
    public String getText() {
        return text;
    }

    @Nullable
    public ExceptionInfo getException() {
        return exception;
    }

    // can't use ImmutableMap since detail can have null values
    public Map<String, /*@Nullable*/Object> getDetail() {
        return detail;
    }

    /*@Pure*/
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("text", text)
                .add("exception", exception)
                .add("detail", detail)
                .toString();
    }

    @JsonCreator
    static TraceError readValue(
            @JsonProperty("text") @Nullable String text,
            @JsonProperty("exception") @Nullable ExceptionInfo exception,
            @JsonProperty("detail") @Nullable Map<String, /*@Nullable*/Object> detail)
            throws JsonMappingException {
        return new TraceError(text, exception, nullToEmpty(detail));
    }
}
