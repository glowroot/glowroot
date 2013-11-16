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
package org.glowroot.container.trace;

import java.util.Map;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import dataflow.quals.Pure;

import static org.glowroot.container.common.ObjectMappers.nullToEmpty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class Message {

    @Nullable
    private final String text;
    // can't use ImmutableMap since detail can have null values
    @Immutable
    private final Map<String, /*@Nullable*/Object> detail;

    protected Message(@Nullable String text, @ReadOnly Map<String, /*@Nullable*/Object> detail) {
        this.text = text;
        this.detail = detail;
    }

    @Nullable
    public String getText() {
        return text;
    }

    // can't use ImmutableMap since detail can have null values
    @Immutable
    public Map<String, /*@Nullable*/Object> getDetail() {
        return detail;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("text", text)
                .add("detail", detail)
                .toString();
    }

    @JsonCreator
    static Message readValue(
            @JsonProperty("text") @Nullable String text,
            @JsonProperty("detail") @Nullable Map<String, /*@Nullable*/Object> detail)
            throws JsonMappingException {
        return new Message(text, nullToEmpty(detail));
    }
}
