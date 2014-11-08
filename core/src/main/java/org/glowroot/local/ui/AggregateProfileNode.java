/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.local.ui;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.markers.UsedByJsonBinding;

import static org.glowroot.common.ObjectMappers.checkNotNullItemsForProperty;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.nullToEmpty;

@UsedByJsonBinding
class AggregateProfileNode {

    // null for synthetic root only
    @Nullable
    private final String stackTraceElement;
    @Nullable
    private final String leafThreadState;
    private int sampleCount;
    private List<String> metricNames;
    private final List<AggregateProfileNode> childNodes;
    private boolean ellipsed;

    static AggregateProfileNode createSyntheticRootNode() {
        return new AggregateProfileNode("<multiple root nodes>");
    }

    static AggregateProfileNode createEllipsedNode() {
        return new AggregateProfileNode("...");
    }

    private AggregateProfileNode(@Nullable String stackTraceElement,
            @Nullable String leafThreadState, int sampleCount, List<String> metricNames,
            List<AggregateProfileNode> childNodes) {
        this.stackTraceElement = stackTraceElement;
        this.leafThreadState = leafThreadState;
        this.sampleCount = sampleCount;
        this.metricNames = metricNames;
        this.childNodes = childNodes;
    }

    private AggregateProfileNode(String stackTraceElement) {
        this.stackTraceElement = stackTraceElement;
        leafThreadState = null;
        metricNames = Lists.newArrayList();
        childNodes = Lists.newArrayList();
    }

    void setMetricNames(List<String> metricNames) {
        this.metricNames = metricNames;
    }

    void incrementSampleCount(int num) {
        sampleCount += num;
    }

    void setEllipsed() {
        ellipsed = true;
    }

    // null for synthetic root only
    @Nullable
    public String getStackTraceElement() {
        return stackTraceElement;
    }

    @Nullable
    public String getLeafThreadState() {
        return leafThreadState;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public List<String> getMetricNames() {
        return metricNames;
    }

    public List<AggregateProfileNode> getChildNodes() {
        return childNodes;
    }

    public boolean isEllipsed() {
        return ellipsed;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("leafThreadState", leafThreadState)
                .add("stackTraceElement", stackTraceElement)
                .add("sampleCount", sampleCount)
                .add("metricNames", metricNames)
                .add("childNodes", childNodes)
                .add("ellipsed", ellipsed)
                .toString();
    }

    @JsonCreator
    static AggregateProfileNode readValue(
            @JsonProperty("stackTraceElement") @Nullable String stackTraceElement,
            @JsonProperty("leafThreadState") @Nullable String leafThreadState,
            @JsonProperty("sampleCount") @Nullable Integer sampleCount,
            @JsonProperty("metricNames") @Nullable List</*@Nullable*/String> uncheckedMetricNames,
            @JsonProperty("childNodes") @Nullable List</*@Nullable*/AggregateProfileNode> uncheckedChildNodes)
            throws JsonMappingException {
        List<String> metricNames =
                checkNotNullItemsForProperty(uncheckedMetricNames, "metricNames");
        List<AggregateProfileNode> childNodes =
                checkNotNullItemsForProperty(uncheckedChildNodes, "childNodes");
        checkRequiredProperty(sampleCount, "sampleCount");
        return new AggregateProfileNode(stackTraceElement, leafThreadState, sampleCount,
                nullToEmpty(metricNames), nullToEmpty(childNodes));
    }
}
