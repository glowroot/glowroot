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
package org.informantproject.stack;

import java.lang.Thread.State;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Element of {@link MergedStackTree}.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MergedStackTreeNode {

    private final StackTraceElement stackTraceElement;
    private final Collection<MergedStackTreeNode> childNodes =
            new ConcurrentLinkedQueue<MergedStackTreeNode>();

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
    // TODO generate a test case that produces multiple thread states for a single node
    private volatile Map<State, Integer> leafThreadStateSampleCounts;
    // optimization for common case where there is just a single thread state recorded
    // so the map (above) doesn't need to be instantiated unless necessary
    private volatile State singleLeafState;

    MergedStackTreeNode(StackTraceElement stackTraceElement) {
        this.stackTraceElement = stackTraceElement;
        sampleCount = 1;
    }

    void addChildNode(MergedStackTreeNode methodTreeElement) {
        childNodes.add(methodTreeElement);
    }

    void addLeafSampling(State threadState) {

        if (singleLeafState == null) {

            // first leaf sampling for this node
            singleLeafState = threadState;

        } else if (!threadState.equals(singleLeafState) && leafThreadStateSampleCounts == null) {

            // first leaf sampling of a different state than the single "best guess" state
            // leaving optimized state, now the map has to be instantiated
            leafThreadStateSampleCounts = new ConcurrentHashMap<State, Integer>();
            leafThreadStateSampleCounts.put(singleLeafState, sampleCount);
        }

        if (leafThreadStateSampleCounts != null) {
            Integer count = leafThreadStateSampleCounts.get(threadState);
            if (count == null) {
                leafThreadStateSampleCounts.put(threadState, 1);
            } else {
                leafThreadStateSampleCounts.put(threadState, count + 1);
            }
        }
    }

    // sampleCount is volatile to ensure visibility, but this method still needs to be called under
    // an appropriate lock so that two threads do not try to increment the count at the same time
    void incrementSampleCount() {
        sampleCount++;
    }

    public Iterable<MergedStackTreeNode> getChildNodes() {
        return childNodes;
    }

    public StackTraceElement getStackTraceElement() {
        return stackTraceElement;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public boolean isLeaf() {
        return leafThreadStateSampleCounts != null || singleLeafState != null;
    }

    public Map<State, Integer> getLeafThreadStateSampleCounts() {
        if (leafThreadStateSampleCounts == null && singleLeafState == null) {
            return Collections.emptyMap();
        } else if (leafThreadStateSampleCounts == null) {
            // optimized for common case with single "best guess" state
            return Collections.singletonMap(singleLeafState, sampleCount);
        } else {
            return leafThreadStateSampleCounts;
        }
    }
}
