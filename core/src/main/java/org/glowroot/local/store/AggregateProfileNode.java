/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.local.store;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;

import org.glowroot.markers.UsedByJsonBinding;

import static org.glowroot.local.store.ObjectMappers.checkRequiredProperty;
import static org.glowroot.local.store.ObjectMappers.orEmpty;

@UsedByJsonBinding
@JsonSerialize(using = AggregateProfileNode.Serializer.class)
public class AggregateProfileNode {

    // null for synthetic root only
    private final @Nullable String stackTraceElement;
    private final @Nullable String leafThreadState;
    private int sampleCount;
    private List<String> metricNames;
    private final List<AggregateProfileNode> childNodes;
    private boolean ellipsed;

    static AggregateProfileNode createSyntheticRootNode() {
        return new AggregateProfileNode("<multiple root nodes>");
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

    void incrementSampleCount(int num) {
        sampleCount += num;
    }

    void setEllipsed() {
        ellipsed = true;
    }

    // null for synthetic root only
    public @Nullable String getStackTraceElement() {
        return stackTraceElement;
    }

    public @Nullable String getLeafThreadState() {
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

    void mergeMatchedNode(AggregateProfileNode toBeMergedNode) {
        incrementSampleCount(toBeMergedNode.getSampleCount());
        // the metric names for a given stack element should always match, unless
        // the line numbers aren't available and overloaded methods are matched up, or
        // the stack trace was captured while one of the synthetic $glowroot$metric$ methods was
        // executing in which case one of the metric names may be a subset of the other,
        // in which case, the superset wins:
        List<String> metricNames = toBeMergedNode.getMetricNames();
        if (metricNames.size() > this.metricNames.size()) {
            this.metricNames = metricNames;
        }
        for (AggregateProfileNode toBeMergedChildNode : toBeMergedNode.getChildNodes()) {
            mergeChildNodeIntoParent(toBeMergedChildNode);
        }
    }

    private void mergeChildNodeIntoParent(AggregateProfileNode toBeMergedChildNode) {
        // for each to-be-merged child node look for a match
        AggregateProfileNode foundMatchingChildNode = null;
        for (AggregateProfileNode childNode : childNodes) {
            if (matches(toBeMergedChildNode, childNode)) {
                foundMatchingChildNode = childNode;
                break;
            }
        }
        if (foundMatchingChildNode == null) {
            childNodes.add(toBeMergedChildNode);
        } else {
            foundMatchingChildNode.mergeMatchedNode(toBeMergedChildNode);
        }
    }

    private static boolean matches(AggregateProfileNode node1, AggregateProfileNode node2) {
        return Objects.equal(node1.getStackTraceElement(), node2.getStackTraceElement())
                && Objects.equal(node1.getLeafThreadState(), node2.getLeafThreadState());
    }

    @JsonCreator
    static AggregateProfileNode readValue(
            @JsonProperty("stackTraceElement") @Nullable String stackTraceElement,
            @JsonProperty("leafThreadState") @Nullable String leafThreadState,
            @JsonProperty("sampleCount") @Nullable Integer sampleCount,
            @JsonProperty("metricNames") @Nullable List</*@Nullable*/String> uncheckedMetricNames,
            @JsonProperty("childNodes") @Nullable List</*@Nullable*/AggregateProfileNode> uncheckedChildNodes)
            throws JsonMappingException {
        List<String> metricNames = orEmpty(uncheckedMetricNames, "metricNames");
        List<AggregateProfileNode> childNodes = orEmpty(uncheckedChildNodes, "childNodes");
        checkRequiredProperty(sampleCount, "sampleCount");
        return new AggregateProfileNode(stackTraceElement, leafThreadState, sampleCount,
                metricNames, childNodes);
    }

    // optimized serializer, don't output unnecessary false booleans and empty collections
    static class Serializer extends JsonSerializer<AggregateProfileNode> {
        @Override
        public void serialize(AggregateProfileNode value, JsonGenerator gen,
                SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("stackTraceElement", value.getStackTraceElement());
            String leafThreadState = value.getLeafThreadState();
            if (leafThreadState != null) {
                gen.writeStringField("leafThreadState", value.getLeafThreadState());
            }
            gen.writeNumberField("sampleCount", value.getSampleCount());
            List<String> metricNames = value.getMetricNames();
            if (!metricNames.isEmpty()) {
                gen.writeArrayFieldStart("metricNames");
                for (String metricName : metricNames) {
                    gen.writeString(metricName);
                }
                gen.writeEndArray();
            }
            List<AggregateProfileNode> childNodes = value.getChildNodes();
            if (!childNodes.isEmpty()) {
                gen.writeArrayFieldStart("childNodes");
                for (AggregateProfileNode childNode : childNodes) {
                    serialize(childNode, gen, serializers);
                }
                gen.writeEndArray();
            }
            boolean ellipsed = value.isEllipsed();
            if (ellipsed) {
                gen.writeBooleanField("ellipsed", ellipsed);
            }
            gen.writeEndObject();
        }
    }
}
