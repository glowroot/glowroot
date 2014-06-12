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
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.markers.UsedByJsonBinding;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.nullToEmpty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@UsedByJsonBinding
class TransactionProfileNode {

    // null for synthetic root only
    @Nullable
    private final String stackTraceElement;
    @Nullable
    private final String leafThreadState;
    private int sampleCount;
    private List<String> traceMetrics;
    private final List<TransactionProfileNode> childNodes;

    private TransactionProfileNode(@Nullable String stackTraceElement,
            @Nullable String leafThreadState, int sampleCount, List<String> traceMetrics,
            List<TransactionProfileNode> childNodes) {
        this.stackTraceElement = stackTraceElement;
        this.leafThreadState = leafThreadState;
        this.sampleCount = sampleCount;
        this.traceMetrics = traceMetrics;
        this.childNodes = childNodes;
    }

    // creates new synthetic root
    TransactionProfileNode() {
        stackTraceElement = null;
        leafThreadState = null;
        traceMetrics = Lists.newArrayList();
        childNodes = Lists.newArrayList();
    }

    void setTraceMetrics(List<String> traceMetrics) {
        this.traceMetrics = traceMetrics;
    }

    void incrementSampleCount(int num) {
        sampleCount += num;
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

    public List<String> getTraceMetrics() {
        return traceMetrics;
    }

    public List<TransactionProfileNode> getChildNodes() {
        return childNodes;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("leafThreadState", leafThreadState)
                .add("stackTraceElement", stackTraceElement)
                .add("sampleCount", sampleCount)
                .add("traceMetrics", traceMetrics)
                .add("childNodes", childNodes)
                .toString();
    }

    @JsonCreator
    static TransactionProfileNode readValue(
            @JsonProperty("stackTraceElement") @Nullable String stackTraceElement,
            @JsonProperty("leafThreadState") @Nullable String leafThreadState,
            @JsonProperty("sampleCount") @Nullable Integer sampleCount,
            @JsonProperty("traceMetrics") @Nullable List<String> traceMetrics,
            @JsonProperty("childNodes") @Nullable List<TransactionProfileNode> childNodes)
            throws JsonMappingException {
        checkRequiredProperty(sampleCount, "sampleCount");
        return new TransactionProfileNode(stackTraceElement, leafThreadState,
                sampleCount, nullToEmpty(traceMetrics), nullToEmpty(childNodes));
    }
}
