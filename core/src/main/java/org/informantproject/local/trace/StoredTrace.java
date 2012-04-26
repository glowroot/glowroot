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

    private String id;
    private long startAt;
    private boolean stuck;
    private long duration; // nanoseconds
    private boolean completed;
    private String description;
    private String username;
    private String attributes;
    private String metrics;
    // using CharSequence so these potentially very large strings can be built using
    // LargeStringBuilder
    private ByteStream spans;
    private ByteStream mergedStackTree;
    // file block ids are stored temporarily in TraceDao while reading the stored trace from the
    // database so that reading from the rolling file can occur outside of the jdbc connection
    private String spansFileBlockId;
    private String mergedStackTreeFileBlockId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getStartAt() {
        return startAt;
    }

    public void setStartAt(long startAt) {
        this.startAt = startAt;
    }

    public boolean isStuck() {
        return stuck;
    }

    public void setStuck(boolean stuck) {
        this.stuck = stuck;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAttributes() {
        return attributes;
    }

    public void setAttributes(String attributes) {
        this.attributes = attributes;
    }

    public String getMetrics() {
        return metrics;
    }

    public void setMetrics(String metrics) {
        this.metrics = metrics;
    }

    public ByteStream getSpans() {
        return spans;
    }

    public void setSpans(ByteStream spans) {
        this.spans = spans;
    }

    public ByteStream getMergedStackTree() {
        return mergedStackTree;
    }

    public void setMergedStackTree(ByteStream mergedStackTree) {
        this.mergedStackTree = mergedStackTree;
    }

    String getSpansFileBlockId() {
        return spansFileBlockId;
    }

    void setSpansFileBlockId(String spansFileBlockId) {
        this.spansFileBlockId = spansFileBlockId;
    }

    String getMergedStackTreeFileBlockId() {
        return mergedStackTreeFileBlockId;
    }

    void setMergedStackTreeFileBlockId(String mergedStackTreeFileBlockId) {
        this.mergedStackTreeFileBlockId = mergedStackTreeFileBlockId;
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
        if (spansFileBlockId != null) {
            toStringHelper.add("spansFileBlockId", spansFileBlockId);
        }
        if (spansFileBlockId != null) {
            toStringHelper.add("mergedStackTreeFileBlockId", mergedStackTreeFileBlockId);
        }
        return toStringHelper.toString();
    }
}
