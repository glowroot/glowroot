/*
 * Copyright 2013-2014 the original author or authors.
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

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.container.common.ObjectMappers.nullToEmpty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class ProfileNode {

    @Nullable
    private final String stackTraceElement;
    @Nullable
    private final String leafThreadState;
    private final int sampleCount;
    private final ImmutableList<String> traceMetrics;
    private final ImmutableList<ProfileNode> childNodes;

    private ProfileNode(@Nullable String stackTraceElement, @Nullable String leafThreadState,
            int sampleCount, List<String> traceMetrics, List<ProfileNode> childNodes) {
        this.stackTraceElement = stackTraceElement;
        this.leafThreadState = leafThreadState;
        this.sampleCount = sampleCount;
        this.traceMetrics = ImmutableList.copyOf(traceMetrics);
        this.childNodes = ImmutableList.copyOf(childNodes);
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

    public ImmutableList<String> getTraceMetrics() {
        return traceMetrics;
    }

    public ImmutableList<ProfileNode> getChildNodes() {
        return childNodes;
    }

    /*@Pure*/
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("stackTraceElement", stackTraceElement)
                .add("leafThreadState", leafThreadState)
                .add("sampleCount", sampleCount)
                .add("traceMetrics", traceMetrics)
                .add("childNodes", childNodes)
                .toString();
    }

    @JsonCreator
    static ProfileNode readValue(
            @JsonProperty("stackTraceElement") @Nullable String stackTraceElement,
            @JsonProperty("leafThreadState") @Nullable String leafThreadState,
            @JsonProperty("sampleCount") @Nullable Integer sampleCount,
            @JsonProperty("traceMetrics") @Nullable List<String> traceMetrics,
            @JsonProperty("childNodes") @Nullable List<ProfileNode> childNodes)
            throws JsonMappingException {
        checkRequiredProperty(sampleCount, "sampleCount");
        return new ProfileNode(stackTraceElement, leafThreadState,
                sampleCount, nullToEmpty(traceMetrics), nullToEmpty(childNodes));
    }
}
