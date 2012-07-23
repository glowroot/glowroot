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

import javax.annotation.Nullable;

import org.informantproject.core.util.ByteStream;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;

/**
 * Structure used as part of the response to "/trace/details".
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class StoredTrace {

    private final String id;
    private final long startAt;
    private final boolean stuck;
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
    private final ByteStream mergedStackTree;

    private StoredTrace(String id, long startAt, boolean stuck, long duration, boolean completed,
            String description, @Nullable String username, @Nullable String attributes,
            @Nullable String metrics, @Nullable ByteStream spans,
            @Nullable ByteStream mergedStackTree) {

        this.id = id;
        this.startAt = startAt;
        this.stuck = stuck;
        this.duration = duration;
        this.completed = completed;
        this.description = description;
        this.username = username;
        this.attributes = attributes;
        this.metrics = metrics;
        this.spans = spans;
        this.mergedStackTree = mergedStackTree;
    }

    public String getId() {
        return id;
    }

    public long getStartAt() {
        return startAt;
    }

    public boolean isStuck() {
        return stuck;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isCompleted() {
        return completed;
    }

    public String getDescription() {
        return description;
    }

    @Nullable
    public String getUsername() {
        return username;
    }

    @Nullable
    public String getAttributes() {
        return attributes;
    }

    @Nullable
    public String getMetrics() {
        return metrics;
    }

    @Nullable
    public ByteStream getSpans() {
        return spans;
    }

    @Nullable
    public ByteStream getMergedStackTree() {
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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String id;
        private long startAt;
        private boolean stuck;
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
        private ByteStream mergedStackTree;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder startAt(long startAt) {
            this.startAt = startAt;
            return this;
        }

        public Builder stuck(boolean stuck) {
            this.stuck = stuck;
            return this;
        }

        public Builder duration(long duration) {
            this.duration = duration;
            return this;
        }

        public Builder completed(boolean completed) {
            this.completed = completed;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder username(@Nullable String username) {
            this.username = username;
            return this;
        }

        public Builder attributes(@Nullable String attributes) {
            this.attributes = attributes;
            return this;
        }

        public Builder metrics(@Nullable String metrics) {
            this.metrics = metrics;
            return this;
        }

        public Builder spans(@Nullable ByteStream spans) {
            this.spans = spans;
            return this;
        }

        public Builder mergedStackTree(@Nullable ByteStream mergedStackTree) {
            this.mergedStackTree = mergedStackTree;
            return this;
        }

        public StoredTrace build() {
            return new StoredTrace(id, startAt, stuck, duration, completed, description, username,
                    attributes, metrics, spans, mergedStackTree);
        }
    }
}
