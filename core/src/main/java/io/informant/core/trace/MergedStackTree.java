/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.core.trace;

import io.informant.util.NotThreadSafe;
import io.informant.util.ThreadSafe;

import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import checkers.igj.quals.ReadOnly;
import checkers.lock.quals.GuardedBy;
import checkers.lock.quals.Holding;
import checkers.nullness.quals.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;

/**
 * Merged stack tree built from sampled stack traces captured by periodic calls to
 * {@link Thread#getStackTrace()}.
 * 
 * This can be either thread-specific stack sampling tied to a trace, or it can be a global sampled
 * stack tree across all threads.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class MergedStackTree {

    private static final Pattern metricMarkerMethodPattern = Pattern
            .compile("^.*\\$informant\\$metric\\$(.*)\\$[0-9]+$");

    private final Object lock = new Object();
    // optimized for trace captures which are never read
    @GuardedBy("lock")
    private final List<List<StackTraceElement>> unmergedStackTraces = Lists.newArrayList();
    @GuardedBy("lock")
    private final List<Thread.State> unmergedStackTraceThreadStates = Lists.newArrayList();
    @GuardedBy("lock")
    private final List<MergedStackTreeNode> rootNodes = Lists.newArrayList();

    public Object getLock() {
        return lock;
    }

    // must be holding lock to call and can only use resulting node tree inside the same
    // synchronized block
    @Holding("lock")
    @Nullable
    public MergedStackTreeNode getRootNode() {
        mergeTheUnmergedStackTraces();
        return MergedStackTreeNode.createSyntheticRoot(rootNodes);
    }

    void addStackTrace(ThreadInfo threadInfo) {
        synchronized (lock) {
            List<StackTraceElement> stackTrace = Arrays.asList(threadInfo.getStackTrace());
            unmergedStackTraces.add(stackTrace);
            unmergedStackTraceThreadStates.add(threadInfo.getThreadState());
            if (unmergedStackTraces.size() >= 10) {
                // merged stack tree takes up less memory, so merge from time to time
                mergeTheUnmergedStackTraces();
            }
        }
    }

    @Holding("lock")
    private void mergeTheUnmergedStackTraces() {
        for (int i = 0; i < unmergedStackTraces.size(); i++) {
            List<StackTraceElement> stackTrace = unmergedStackTraces.get(i);
            Thread.State threadState = unmergedStackTraceThreadStates.get(i);
            addToStackTree(stripSyntheticMetricMethods(stackTrace), threadState);
        }
        unmergedStackTraces.clear();
        unmergedStackTraceThreadStates.clear();
    }

    @Holding("lock")
    @VisibleForTesting
    public void addToStackTree(@ReadOnly List<StackTraceElementPlus> stackTrace,
            Thread.State threadState) {
        MergedStackTreeNode lastMatchedNode = null;
        List<MergedStackTreeNode> nextChildNodes = rootNodes;
        int nextIndex;
        // navigate the stack tree nodes
        // matching the new stack trace as far as possible
        for (nextIndex = stackTrace.size() - 1; nextIndex >= 0; nextIndex--) {
            StackTraceElementPlus element = stackTrace.get(nextIndex);
            // check all child nodes
            boolean matchFound = false;
            for (MergedStackTreeNode childNode : nextChildNodes) {
                if (matches(element.getStackTraceElement(), childNode, nextIndex == 0,
                        threadState)) {
                    // match found, update lastMatchedNode and break out of the inner loop
                    childNode.incrementSampleCount();
                    // the metric names for a given stack element should always match, unless
                    // the line numbers aren't available and overloaded methods are matched up, or
                    // the stack trace was captured while one of the synthetic $metric$ methods was
                    // executing in which case one of the metric names may be a subset of the other
                    // TODO handle the first case better? (overloaded methods with no line numbers)
                    // for the second case, the superset wins:
                    List<String> metricNames = element.getMetricNames();
                    if (metricNames != null
                            && metricNames.size() > childNode.getMetricNames().size()) {
                        childNode.setMetricNames(metricNames);
                    }
                    lastMatchedNode = childNode;
                    nextChildNodes = lastMatchedNode.getChildNodes();
                    matchFound = true;
                    break;
                }
            }
            if (!matchFound) {
                break;
            }
        }
        // add remaining stack trace elements
        for (int i = nextIndex; i >= 0; i--) {
            StackTraceElementPlus element = stackTrace.get(i);
            MergedStackTreeNode nextNode = MergedStackTreeNode.create(
                    element.getStackTraceElement(), element.getMetricNames());
            if (i == 0) {
                // leaf node
                nextNode.setLeafThreadState(threadState);
            }
            if (lastMatchedNode == null) {
                // new root node
                rootNodes.add(nextNode);
            } else {
                lastMatchedNode.addChildNode(nextNode);
            }
            lastMatchedNode = nextNode;
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("rootNodes", rootNodes)
                .toString();
    }

    // recreate the stack trace as it would have been without the synthetic $metric$ methods
    public static List<StackTraceElementPlus> stripSyntheticMetricMethods(
            @ReadOnly List<StackTraceElement> stackTrace) {

        List<StackTraceElementPlus> stackTracePlus = Lists.newArrayListWithCapacity(
                stackTrace.size());
        for (Iterator<StackTraceElement> i = stackTrace.iterator(); i.hasNext();) {
            StackTraceElement element = i.next();
            String metricName = getMetricName(element);
            if (metricName != null) {
                String originalMethodName = element.getMethodName();
                List<String> metricNames = Lists.newArrayList();
                metricNames.add(metricName);
                // skip over successive $metric$ methods up to and including the "original" method
                while (i.hasNext()) {
                    StackTraceElement skipElement = i.next();
                    metricName = getMetricName(skipElement);
                    if (metricName == null) {
                        // loop should always terminate here since synthetic $metric$ methods should
                        // never be the last element (the last element is the first element in the
                        // call stack)
                        originalMethodName = skipElement.getMethodName();
                        break;
                    }
                    metricNames.add(metricName);
                }
                // "original" in the sense that this is what it would have been without the
                // synthetic $metric$ methods
                StackTraceElement originalElement = new StackTraceElement(element.getClassName(),
                        originalMethodName, element.getFileName(), element.getLineNumber());
                stackTracePlus.add(new StackTraceElementPlus(originalElement, metricNames));
            } else {
                stackTracePlus.add(new StackTraceElementPlus(element, null));
            }
        }
        return stackTracePlus;
    }

    @Nullable
    private static String getMetricName(StackTraceElement stackTraceElement) {
        Matcher matcher = metricMarkerMethodPattern.matcher(stackTraceElement.getMethodName());
        if (matcher.matches()) {
            return matcher.group(1).replace("$", " ");
        } else {
            return null;
        }
    }

    private static boolean matches(StackTraceElement stackTraceElement,
            MergedStackTreeNode childNode, boolean leaf, Thread.State threadState) {

        Thread.State leafThreadState = childNode.getLeafThreadState();
        if (leafThreadState != null && leaf) {
            // only consider thread state when matching the leaf node
            return stackTraceElement.equals(childNode.getStackTraceElement())
                    && threadState == leafThreadState;
        } else if (leafThreadState == null && !leaf) {
            return stackTraceElement.equals(childNode.getStackTraceElement());
        } else {
            return false;
        }
    }

    @NotThreadSafe
    public static class StackTraceElementPlus {
        private final StackTraceElement stackTraceElement;
        @ReadOnly
        @Nullable
        private final List<String> metricNames;
        private StackTraceElementPlus(StackTraceElement stackTraceElement,
                @ReadOnly @Nullable List<String> metricNames) {
            this.stackTraceElement = stackTraceElement;
            this.metricNames = metricNames;
        }
        public StackTraceElement getStackTraceElement() {
            return stackTraceElement;
        }
        @ReadOnly
        @Nullable
        public List<String> getMetricNames() {
            return metricNames;
        }
    }
}
