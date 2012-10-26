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
package io.informant.core.trace;

import java.lang.Thread.State;
import java.lang.management.ThreadInfo;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;

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
// TODO it would be more efficient to store stack traces unmerged up until some point
// (e.g. to optimize for trace captures which are never stored)
// in this case, it should be configurable how many stack traces to store unmerged
// after which the existing stack traces are merged as well as future stack traces
@ThreadSafe
public class MergedStackTree {

    private static final Pattern metricMarkerMethodPattern = Pattern
            .compile("^.*\\$informant\\$metric\\$(.*)\\$[0-9]+$");

    private final Collection<MergedStackTreeNode> rootNodes = Queues.newConcurrentLinkedQueue();

    // marked transient for gson serialization
    private final transient Object lock = new Object();

    @Nullable
    public MergedStackTreeNode getRootNode() {
        List<MergedStackTreeNode> rootNodes = Lists.newArrayList(this.rootNodes);
        if (rootNodes.size() == 0) {
            return null;
        } else if (rootNodes.size() == 1) {
            return rootNodes.get(0);
        } else {
            int totalSampleCount = 0;
            for (MergedStackTreeNode rootNode : rootNodes) {
                totalSampleCount += rootNode.getSampleCount();
            }
            MergedStackTreeNode syntheticRootNode = MergedStackTreeNode
                    .createSyntheticRoot(totalSampleCount);
            for (MergedStackTreeNode rootNode : rootNodes) {
                syntheticRootNode.addChildNode(rootNode);
            }
            return syntheticRootNode;
        }
    }

    public void addStackTrace(ThreadInfo threadInfo) {
        synchronized (lock) {
            // TODO put into list, then merge every 100, or whenever merge is requested
            StackTraceElement[] stackTrace = threadInfo.getStackTrace();
            addToStackTree(stackTrace, threadInfo.getThreadState());
        }
    }

    @VisibleForTesting
    public void addToStackTree(StackTraceElement[] stackTrace, State threadState) {
        addToStackTree(stripSyntheticMetricMethods(stackTrace), threadState);
    }

    private void addToStackTree(List<StackTraceElementPlus> stackTrace, State threadState) {
        MergedStackTreeNode lastMatchedNode = null;
        Iterable<MergedStackTreeNode> nextChildNodes = rootNodes;
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
                    // match found, update lastMatchedNode and continue
                    childNode.incrementSampleCount();
                    List<String> metricNames = element.getMetricNames();
                    if (metricNames != null) {
                        childNode.addAllAbsentMetricNames(metricNames);
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
            StackTraceElement[] stackTrace) {

        List<StackTraceElementPlus> stackTracePlus = Lists
                .newArrayListWithCapacity(stackTrace.length);
        for (int i = 0; i < stackTrace.length; i++) {
            StackTraceElement element = stackTrace[i];
            String metricName = getMetricName(element);
            if (metricName != null) {
                String originalMethodName = stackTrace[i].getMethodName();
                List<String> metricNames = Lists.newArrayList(metricName);
                // skip over successive $metric$ methods up to and including the "original" method
                while (++i < stackTrace.length) {
                    metricName = getMetricName(stackTrace[i]);
                    if (metricName == null) {
                        // loop should always terminate here since synthetic $metric$ methods should
                        // never be the last element (the last element is the first element in the
                        // call stack)
                        originalMethodName = stackTrace[i].getMethodName();
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
            MergedStackTreeNode childNode, boolean leaf, State threadState) {

        if (childNode.isLeaf() && leaf) {
            // only consider thread state when matching the leaf node
            return stackTraceElement.equals(childNode.getStackTraceElement())
                    && threadState == childNode.getLeafThreadState();
        } else if (!childNode.isLeaf() && !leaf) {
            return stackTraceElement.equals(childNode.getStackTraceElement());
        } else {
            return false;
        }
    }

    public static class StackTraceElementPlus {
        private final StackTraceElement stackTraceElement;
        @Nullable
        private final List<String> metricNames;
        private StackTraceElementPlus(StackTraceElement stackTraceElement,
                @Nullable List<String> metricNames) {
            this.stackTraceElement = stackTraceElement;
            this.metricNames = metricNames;
        }
        public StackTraceElement getStackTraceElement() {
            return stackTraceElement;
        }
        @Nullable
        public List<String> getMetricNames() {
            return metricNames;
        }
    }
}
