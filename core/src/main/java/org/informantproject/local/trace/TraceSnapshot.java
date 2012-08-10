/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.local.trace;

import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.informantproject.core.util.ByteStream;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.collect.ImmutableMap;

/**
 * Structure used as part of the response to "/trace/details".
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class TraceSnapshot {

    private final String id;
    private final long startAt;
    private final boolean stuck;
    private final boolean error;
    private final long duration; // nanoseconds
    private final boolean completed;
    private final String description;
    @Nullable
    private final String username;
    @Nullable
    private final String attributes;
    @Nullable
    private final String metrics;
    // using ByteStream so these potentially very large strings can be streamed without consuming
    // large amounts of memory
    @Nullable
    private final ByteStream spans;
    @Nullable
    private final ImmutableMap<String, String> spanStackTraces;
    @Nullable
    private final ByteStream mergedStackTree;

    private TraceSnapshot(String id, long startAt, boolean stuck, boolean error, long duration,
            boolean completed, String description, @Nullable String username,
            @Nullable String attributes, @Nullable String metrics, @Nullable ByteStream spans,
            @Nullable ImmutableMap<String, String> spanStackTraces,
            @Nullable ByteStream mergedStackTree) {

        this.id = id;
        this.startAt = startAt;
        this.stuck = stuck;
        this.error = error;
        this.duration = duration;
        this.completed = completed;
        this.description = description;
        this.username = username;
        this.attributes = attributes;
        this.metrics = metrics;
        this.spans = spans;
        this.mergedStackTree = mergedStackTree;
        this.spanStackTraces = spanStackTraces;
    }

    String getId() {
        return id;
    }

    long getStartAt() {
        return startAt;
    }

    boolean isStuck() {
        return stuck;
    }

    boolean isError() {
        return error;
    }

    long getDuration() {
        return duration;
    }

    boolean isCompleted() {
        return completed;
    }

    String getDescription() {
        return description;
    }

    @Nullable
    String getUsername() {
        return username;
    }

    @Nullable
    String getAttributes() {
        return attributes;
    }

    @Nullable
    String getMetrics() {
        return metrics;
    }

    @Nullable
    ByteStream getSpans() {
        return spans;
    }

    @Nullable
    Map<String, String> getSpanStackTraces() {
        return spanStackTraces;
    }

    @Nullable
    ByteStream getMergedStackTree() {
        return mergedStackTree;
    }

    @Override
    public String toString() {
        ToStringHelper toStringHelper = Objects.toStringHelper(this)
                .add("id", id)
                .add("startAt", startAt)
                .add("stuck", stuck)
                .add("duration", duration)
                .add("completed", completed)
                .add("description", description)
                .add("username", username)
                .add("attributes", attributes)
                .add("metrics", metrics);
        return toStringHelper.toString();
    }

    static Builder builder() {
        return new Builder();
    }

    static class Builder {

        private String id;
        private long startAt;
        private boolean stuck;
        private boolean error;
        private long duration;
        private boolean completed;
        private String description;
        @Nullable
        private String username;
        @Nullable
        private String attributes;
        @Nullable
        private String metrics;
        @Nullable
        private ByteStream spans;
        @Nullable
        private ImmutableMap<String, String> spanStackTraces;
        @Nullable
        private ByteStream mergedStackTree;

        private Builder() {}

        Builder id(String id) {
            this.id = id;
            return this;
        }

        Builder startAt(long startAt) {
            this.startAt = startAt;
            return this;
        }

        Builder stuck(boolean stuck) {
            this.stuck = stuck;
            return this;
        }

        Builder error(boolean error) {
            this.error = error;
            return this;
        }

        Builder duration(long duration) {
            this.duration = duration;
            return this;
        }

        Builder completed(boolean completed) {
            this.completed = completed;
            return this;
        }

        Builder description(String description) {
            this.description = description;
            return this;
        }

        Builder username(@Nullable String username) {
            this.username = username;
            return this;
        }

        Builder attributes(@Nullable String attributes) {
            this.attributes = attributes;
            return this;
        }

        Builder metrics(@Nullable String metrics) {
            this.metrics = metrics;
            return this;
        }

        Builder spans(@Nullable ByteStream spans) {
            this.spans = spans;
            return this;
        }

        Builder spanStackTraces(@Nullable ImmutableMap<String, String> spanStackTraces) {
            this.spanStackTraces = spanStackTraces;
            return this;
        }

        Builder mergedStackTree(@Nullable ByteStream mergedStackTree) {
            this.mergedStackTree = mergedStackTree;
            return this;
        }

        TraceSnapshot build() {
            return new TraceSnapshot(id, startAt, stuck, error, duration, completed, description,
                    username, attributes, metrics, spans, spanStackTraces, mergedStackTree);
        }
    }
}
