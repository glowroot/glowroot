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
package org.informantproject.core.trace;

import java.lang.Thread.State;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;

/**
 * Element of {@link MergedStackTree}.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class MergedStackTreeNode {

    @Nullable
    private final StackTraceElement stackTraceElement;
    private final Collection<MergedStackTreeNode> childNodes = Lists.newCopyOnWriteArrayList();
    // using List over Set in order to preserve ordering
    private final CopyOnWriteArrayList<String> metricNames;

    // these must be volatile since they are updated by one thread (the stack trace sampling
    // thread) and can be read by another thread (e.g. the executing thread, the stuck trace
    // alerting thread and real-time monitoring threads)
    //
    // since the stack traces are performed under a synchronized lock (see MergedStackTree)
    // there's no need to worry about concurrent updates which avoids the (slight) overhead
    // of using AtomicInteger
    //
    // TODO maybe reads should be performed under the same synchronized lock as the writes
    // (see MergedStackTree) in order to avoid volatile and ensure consistent state of read
    //
    private volatile int sampleCount;
    @Nullable
    private volatile State leafThreadState;

    // this is for creating a single synthetic root node above other root nodes when there are
    // multiple root nodes
    static MergedStackTreeNode createSyntheticRoot(int sampleCount) {
        return new MergedStackTreeNode(null, null, sampleCount);
    }

    static MergedStackTreeNode create(StackTraceElement stackTraceElement,
            @Nullable Collection<String> metricNames) {
        return new MergedStackTreeNode(stackTraceElement, metricNames, 1);
    }

    private MergedStackTreeNode(@Nullable StackTraceElement stackTraceElement,
            @Nullable Collection<String> metricNames, int sampleCount) {

        this.stackTraceElement = stackTraceElement;
        if (metricNames == null) {
            this.metricNames = Lists.newCopyOnWriteArrayList();
        } else {
            this.metricNames = Lists.newCopyOnWriteArrayList(metricNames);
        }
        this.sampleCount = sampleCount;
    }

    void addChildNode(MergedStackTreeNode methodTreeElement) {
        childNodes.add(methodTreeElement);
    }

    void addAllAbsentMetricNames(Collection<String> metricNames) {
        this.metricNames.addAllAbsent(metricNames);
    }

    void setLeafThreadState(State leafThreadState) {
        this.leafThreadState = leafThreadState;
    }

    // sampleCount is volatile to ensure visibility, but this method still needs to be called under
    // an appropriate lock so that two threads do not try to increment the count at the same time
    void incrementSampleCount() {
        sampleCount++;
    }

    public boolean isSyntheticRoot() {
        return stackTraceElement == null;
    }

    public Collection<MergedStackTreeNode> getChildNodes() {
        return childNodes;
    }

    public Collection<String> getMetricNames() {
        return metricNames;
    }

    // only returns null for synthetic root
    @Nullable
    public StackTraceElement getStackTraceElement() {
        return stackTraceElement;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public boolean isLeaf() {
        return leafThreadState != null;
    }

    @Nullable
    public State getLeafThreadState() {
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
}
