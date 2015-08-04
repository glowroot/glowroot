/*
 * Copyright 2013-2015 the original author or authors.
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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.container.common.ObjectMappers.orEmpty;

public class ProfileNode {

    private final @Nullable String stackTraceElement;
    private final @Nullable String leafThreadState;
    private final int sampleCount;
    private final ImmutableList<String> timerNames;
    private final ImmutableList<ProfileNode> childNodes;

    private ProfileNode(@Nullable String stackTraceElement, @Nullable String leafThreadState,
            int sampleCount, List<String> timerNames, List<ProfileNode> childNodes) {
        this.stackTraceElement = stackTraceElement;
        this.leafThreadState = leafThreadState;
        this.sampleCount = sampleCount;
        this.timerNames = ImmutableList.copyOf(timerNames);
        this.childNodes = ImmutableList.copyOf(childNodes);
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

    public ImmutableList<String> getTimerNames() {
        return timerNames;
    }

    public ImmutableList<ProfileNode> getChildNodes() {
        return childNodes;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("stackTraceElement", stackTraceElement)
                .add("leafThreadState", leafThreadState)
                .add("sampleCount", sampleCount)
                .add("timerNames", timerNames)
                .add("childNodes", childNodes)
                .toString();
    }

    @JsonCreator
    static ProfileNode readValue(
            @JsonProperty("stackTraceElement") @Nullable String stackTraceElement,
            @JsonProperty("leafThreadState") @Nullable String leafThreadState,
            @JsonProperty("sampleCount") @Nullable Integer sampleCount,
            @JsonProperty("timerNames") @Nullable List</*@Nullable*/String> uncheckedTimerNames,
            @JsonProperty("childNodes") @Nullable List</*@Nullable*/ProfileNode> uncheckedChildNodes)
                    throws JsonMappingException {
        List<String> timerNames = orEmpty(uncheckedTimerNames, "timerNames");
        List<ProfileNode> childNodes = orEmpty(uncheckedChildNodes, "childNodes");
        checkRequiredProperty(sampleCount, "sampleCount");
        return new ProfileNode(stackTraceElement, leafThreadState, sampleCount, timerNames,
                childNodes);
    }
}
