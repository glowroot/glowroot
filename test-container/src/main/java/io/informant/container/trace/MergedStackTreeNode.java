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

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import static io.informant.container.common.ObjectMappers.checkRequiredProperty;
import static io.informant.container.common.ObjectMappers.nullToEmpty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class MergedStackTreeNode {

    @Nullable
    private final String stackTraceElement;
    private final ImmutableList<MergedStackTreeNode> childNodes;
    private final ImmutableList<String> metricNames;
    private final int sampleCount;
    @Nullable
    private final String leafThreadState;

    public MergedStackTreeNode(@Nullable String stackTraceElement,
            @ReadOnly List<MergedStackTreeNode> childNodes, @ReadOnly List<String> metricNames,
            int sampleCount, @Nullable String leafThreadState) {
        this.stackTraceElement = stackTraceElement;
        this.childNodes = ImmutableList.copyOf(childNodes);
        this.metricNames = ImmutableList.copyOf(metricNames);
        this.sampleCount = sampleCount;
        this.leafThreadState = leafThreadState;
    }

    // null for synthetic root only
    @Nullable
    public String getStackTraceElement() {
        return stackTraceElement;
    }

    public ImmutableList<MergedStackTreeNode> getChildNodes() {
        return childNodes;
    }

    public ImmutableList<String> getMetricNames() {
        return metricNames;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    @Nullable
    public String getLeafThreadState() {
        return leafThreadState;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("stackTraceElement", stackTraceElement)
                .add("childNodes", childNodes)
                .add("metricNames", metricNames)
                .add("sampleCount", sampleCount)
                .add("leafThreadState", leafThreadState)
                .toString();
    }

    @JsonCreator
    static MergedStackTreeNode readValue(
            @JsonProperty("stackTraceElement") @Nullable String stackTraceElement,
            @JsonProperty("childNodes") @Nullable List<MergedStackTreeNode> childNodes,
            @JsonProperty("metricNames") @Nullable List<String> metricNames,
            @JsonProperty("sampleCount") @Nullable Integer sampleCount,
            @JsonProperty("leafThreadState") @Nullable String leafThreadState)
            throws JsonMappingException {
        checkRequiredProperty(sampleCount, "sampleCount");
        return new MergedStackTreeNode(stackTraceElement, nullToEmpty(childNodes),
                nullToEmpty(metricNames), sampleCount, leafThreadState);
    }
}
