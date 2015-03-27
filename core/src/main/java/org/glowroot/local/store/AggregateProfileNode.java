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

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.orEmpty;

@UsedByJsonBinding
@JsonSerialize(using = AggregateProfileNode.Serializer.class)
public class AggregateProfileNode {

    // null for synthetic root only
    private final @Nullable String stackTraceElement;
    private final @Nullable String leafThreadState;
    private int sampleCount;
    private List<String> timerNames;
    private final List<AggregateProfileNode> childNodes;
    private boolean ellipsed;

    static AggregateProfileNode createSyntheticRootNode() {
        return new AggregateProfileNode("<multiple root nodes>");
    }

    private AggregateProfileNode(@Nullable String stackTraceElement,
            @Nullable String leafThreadState, int sampleCount, List<String> timerNames,
            List<AggregateProfileNode> childNodes) {
        this.stackTraceElement = stackTraceElement;
        this.leafThreadState = leafThreadState;
        this.sampleCount = sampleCount;
        this.timerNames = timerNames;
        this.childNodes = childNodes;
    }

    private AggregateProfileNode(String stackTraceElement) {
        this.stackTraceElement = stackTraceElement;
        leafThreadState = null;
        timerNames = Lists.newArrayList();
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

    public List<String> getTimerNames() {
        return timerNames;
    }

    public List<AggregateProfileNode> getChildNodes() {
        return childNodes;
    }

    public boolean isEllipsed() {
        return ellipsed;
    }

    void mergeMatchedNode(AggregateProfileNode toBeMergedNode) {
        incrementSampleCount(toBeMergedNode.getSampleCount());
        // the timer names for a given stack element should always match, unless
        // the line numbers aren't available and overloaded methods are matched up, or
        // the stack trace was captured while one of the synthetic $glowroot$timer$ methods was
        // executing in which case one of the timer names may be a subset of the other,
        // in which case, the superset wins:
        List<String> timerNames = toBeMergedNode.getTimerNames();
        if (timerNames.size() > this.timerNames.size()) {
            this.timerNames = timerNames;
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
            @JsonProperty("timerNames") @Nullable List</*@Nullable*/String> uncheckedTimerNames,
            @JsonProperty("childNodes") @Nullable List</*@Nullable*/AggregateProfileNode> uncheckedChildNodes)
            throws JsonMappingException {
        List<String> timerNames = orEmpty(uncheckedTimerNames, "timerNames");
        List<AggregateProfileNode> childNodes = orEmpty(uncheckedChildNodes, "childNodes");
        checkRequiredProperty(sampleCount, "sampleCount");
        return new AggregateProfileNode(stackTraceElement, leafThreadState, sampleCount,
                timerNames, childNodes);
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
            List<String> timerNames = value.getTimerNames();
            if (!timerNames.isEmpty()) {
                gen.writeArrayFieldStart("timerNames");
                for (String timerName : timerNames) {
                    gen.writeString(timerName);
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
