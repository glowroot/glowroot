/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.snapshot;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.LazyNonNull;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.io.CharSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structure used as part of the response to "/explorer/detail".
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class Snapshot {

    private final String id;
    private final long start; // milliseconds since 01/01/1970, 00:00:00 GMT
    private final long duration; // nanoseconds
    private final boolean stuck;
    private final boolean completed;
    private final boolean background;
    private final String headline;
    @Nullable
    private final String attributes; // json data
    @Nullable
    private final String userId;
    @Nullable
    private final String errorText;
    @Nullable
    private final String errorDetail; // json data
    @Nullable
    private final String exception; // json data
    @Nullable
    private final String metrics; // json data
    // using CharSource so these potentially very large strings can be streamed without consuming
    // large amounts of memory
    @Nullable
    private final CharSource spans; // json data
    @Nullable
    private final CharSource coarseMergedStackTree; // json data
    @Nullable
    private final CharSource fineMergedStackTree; // json data

    private Snapshot(String id, long start, long duration, boolean stuck, boolean completed,
            boolean background, String headline, @Nullable String attributes,
            @Nullable String userId, @Nullable String errorText, @Nullable String errorDetail,
            @Nullable String exception, @Nullable String metrics,
            @Immutable @Nullable CharSource spans,
            @Immutable @Nullable CharSource coarseMergedStackTree,
            @Immutable @Nullable CharSource fineMergedStackTree) {
        this.id = id;
        this.start = start;
        this.duration = duration;
        this.stuck = stuck;
        this.completed = completed;
        this.background = background;
        this.headline = headline;
        this.attributes = attributes;
        this.userId = userId;
        this.errorText = errorText;
        this.errorDetail = errorDetail;
        this.exception = exception;
        this.metrics = metrics;
        this.spans = spans;
        this.coarseMergedStackTree = coarseMergedStackTree;
        this.fineMergedStackTree = fineMergedStackTree;
    }

    public String getId() {
        return id;
    }

    public long getStart() {
        return start;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isStuck() {
        return stuck;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isBackground() {
        return background;
    }

    public String getHeadline() {
        return headline;
    }

    @Nullable
    public String getAttributes() {
        return attributes;
    }

    @Nullable
    public String getUserId() {
        return userId;
    }

    @Nullable
    public String getErrorText() {
        return errorText;
    }

    @Nullable
    public String getErrorDetail() {
        return errorDetail;
    }

    @Nullable
    public String getException() {
        return exception;
    }

    @Nullable
    public String getMetrics() {
        return metrics;
    }

    @Immutable
    @Nullable
    public CharSource getSpans() {
        return spans;
    }

    @Immutable
    @Nullable
    public CharSource getCoarseMergedStackTree() {
        return coarseMergedStackTree;
    }

    @Immutable
    @Nullable
    public CharSource getFineMergedStackTree() {
        return fineMergedStackTree;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("start", start)
                .add("duration", duration)
                .add("stuck", stuck)
                .add("completed", completed)
                .add("background", background)
                .add("headline", headline)
                .add("attributes", attributes)
                .add("userId", userId)
                .add("errorText", errorText)
                .add("errorDetail", errorDetail)
                .add("exception", exception)
                .add("metrics", metrics)
                .toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private static final Logger logger = LoggerFactory.getLogger(Builder.class);

        @LazyNonNull
        private String id;
        private long start;
        private long duration;
        private boolean stuck;
        private boolean completed;
        private boolean background;
        @LazyNonNull
        private String headline;
        @Nullable
        private String attributes;
        @Nullable
        private String userId;
        @Nullable
        private String errorText;
        @Nullable
        private String errorDetail;
        @Nullable
        private String exception;
        @Nullable
        private String metrics;
        @Immutable
        @Nullable
        private CharSource spans;
        @Immutable
        @Nullable
        private CharSource coarseMergedStackTree;
        @Immutable
        @Nullable
        private CharSource fineMergedStackTree;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder start(long start) {
            this.start = start;
            return this;
        }

        public Builder duration(long duration) {
            this.duration = duration;
            return this;
        }

        public Builder stuck(boolean stuck) {
            this.stuck = stuck;
            return this;
        }

        public Builder completed(boolean completed) {
            this.completed = completed;
            return this;
        }

        public Builder background(boolean background) {
            this.background = background;
            return this;
        }

        public Builder headline(String headline) {
            this.headline = headline;
            return this;
        }

        public Builder attributes(@Nullable String attributes) {
            this.attributes = attributes;
            return this;
        }

        public Builder userId(@Nullable String userId) {
            this.userId = userId;
            return this;
        }

        public Builder errorText(@Nullable String errorText) {
            this.errorText = errorText;
            return this;
        }

        public Builder errorDetail(@Nullable String errorDetail) {
            this.errorDetail = errorDetail;
            return this;
        }

        public Builder exception(@Nullable String exception) {
            this.exception = exception;
            return this;
        }

        public Builder metrics(@Nullable String metrics) {
            this.metrics = metrics;
            return this;
        }

        public Builder spans(@Immutable @Nullable CharSource spans) {
            this.spans = spans;
            return this;
        }

        public Builder coarseMergedStackTree(
                @Immutable @Nullable CharSource coarseMergedStackTree) {
            this.coarseMergedStackTree = coarseMergedStackTree;
            return this;
        }

        public Builder fineMergedStackTree(@Immutable @Nullable CharSource fineMergedStackTree) {
            this.fineMergedStackTree = fineMergedStackTree;
            return this;
        }

        public Snapshot build() {
            if (id == null) {
                logger.warn("setId() must be called before build()");
                headline = "<error: no id provided>";
            }
            if (headline == null) {
                logger.warn("setHeadline() must be called before build()");
                headline = "<error: no headline provided>";
            }
            return new Snapshot(id, start, duration, stuck, completed, background,
                    headline, attributes, userId, errorText, errorDetail, exception,
                    metrics, spans, coarseMergedStackTree, fineMergedStackTree);
        }
    }
}
