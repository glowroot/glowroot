/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.trace.model;

import java.lang.Thread.State;
import java.util.List;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Element of {@link Profile}.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ProfileNode {

    @Nullable
    private final StackTraceElement stackTraceElement;
    @Nullable
    private final State leafThreadState;
    private int sampleCount;
    // using List over Set in order to preserve ordering
    // may contain duplicates (common from weaving groups of overloaded methods), these are filtered
    // out later when profile is written to json
    @MonotonicNonNull
    private ImmutableList<String> metricNames;
    // nodes mostly have a single child node, and rarely have more than two child nodes
    private final List<ProfileNode> childNodes = Lists.newArrayListWithCapacity(2);

    public static ProfileNode createSyntheticRoot() {
        return new ProfileNode(null, null);
    }

    public static ProfileNode create(StackTraceElement stackTraceElement,
            @Nullable State leafThreadState) {
        return new ProfileNode(stackTraceElement, leafThreadState);
    }

    private ProfileNode(@Nullable StackTraceElement stackTraceElement,
            @Nullable State leafThreadState) {
        this.stackTraceElement = stackTraceElement;
        this.leafThreadState = leafThreadState;
    }

    public void addChildNode(ProfileNode node) {
        childNodes.add(node);
    }

    // may contain duplicates
    public void setMetricNames(List<String> metricNames) {
        this.metricNames = ImmutableList.copyOf(metricNames);
    }

    // sampleCount is volatile to ensure visibility, but this method still needs to be called under
    // an appropriate lock so that two threads do not try to increment the count at the same time
    public void incrementSampleCount(int num) {
        sampleCount += num;
    }

    // only returns null for synthetic root
    @Nullable
    public StackTraceElement getStackTraceElement() {
        return stackTraceElement;
    }

    @Nullable
    public State getLeafThreadState() {
        return leafThreadState;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    // may contain duplicates
    public ImmutableList<String> getMetricNames() {
        if (metricNames == null) {
            return ImmutableList.of();
        } else {
            return metricNames;
        }
    }

    public List<ProfileNode> getChildNodes() {
        return childNodes;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("stackTraceElement", stackTraceElement)
                .add("leafThreadState", leafThreadState)
                .add("sampleCount", sampleCount)
                .add("metricNames", metricNames)
                .add("childNodes", childNodes)
                .toString();
    }
}
