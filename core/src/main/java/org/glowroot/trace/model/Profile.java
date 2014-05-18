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
import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Merged stack tree built from sampled stack traces captured by periodic calls to
 * {@link Thread#getStackTrace()}.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class Profile {

    private static final Pattern metricMarkerMethodPattern =
            Pattern.compile("^.*\\$glowroot\\$trace\\$metric\\$(.*)\\$[0-9]+$");

    private final Object lock = new Object();
    // optimized for trace captures which are never read
    @GuardedBy("lock")
    private final List<List<StackTraceElement>> unmergedStackTraces = Lists.newArrayList();
    @GuardedBy("lock")
    private final List<State> unmergedStackTraceThreadStates = Lists.newArrayList();
    @GuardedBy("lock")
    private final ProfileNode syntheticRootNode = ProfileNode.createSyntheticRoot();

    public Object getLock() {
        return lock;
    }

    // must be holding lock to call and can only use resulting node tree inside the same
    // synchronized block
    public ProfileNode getSyntheticRootNode() {
        mergeTheUnmergedStackTraces();
        return syntheticRootNode;
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

    // must be holding lock to call
    private void mergeTheUnmergedStackTraces() {
        for (int i = 0; i < unmergedStackTraces.size(); i++) {
            List<StackTraceElement> stackTrace = unmergedStackTraces.get(i);
            State threadState = unmergedStackTraceThreadStates.get(i);
            addToStackTree(stripSyntheticMetricMethods(stackTrace), threadState);
        }
        unmergedStackTraces.clear();
        unmergedStackTraceThreadStates.clear();
    }

    // must be holding lock to call
    @VisibleForTesting
    public void addToStackTree(List<StackTraceElementPlus> stackTrace, State threadState) {
        syntheticRootNode.incrementSampleCount(1);
        ProfileNode lastMatchedNode = syntheticRootNode;
        List<ProfileNode> nextChildNodes = syntheticRootNode.getChildNodes();
        int nextIndex;
        // navigate the stack tree nodes
        // matching the new stack trace as far as possible
        for (nextIndex = stackTrace.size() - 1; nextIndex >= 0; nextIndex--) {
            StackTraceElementPlus element = stackTrace.get(nextIndex);
            // check all child nodes
            boolean matchFound = false;
            for (ProfileNode childNode : nextChildNodes) {
                if (matches(element.getStackTraceElement(), childNode, nextIndex == 0,
                        threadState)) {
                    // match found, update lastMatchedNode and break out of the inner loop
                    childNode.incrementSampleCount(1);
                    // the metric names for a given stack element should always match, unless
                    // the line numbers aren't available and overloaded methods are matched up, or
                    // the stack trace was captured while one of the synthetic $trace$metric$
                    // methods was executing in which case one of the metric names may be a subset
                    // of the other, in which case, the superset wins:
                    List<String> traceMetrics = element.getTraceMetrics();
                    if (traceMetrics != null
                            && traceMetrics.size() > childNode.getTraceMetrics().size()) {
                        childNode.setTraceMetrics(traceMetrics);
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
            ProfileNode nextNode;
            if (i == 0) {
                // leaf node
                nextNode = ProfileNode.create(element.getStackTraceElement(), threadState);
            } else {
                nextNode = ProfileNode.create(element.getStackTraceElement(), null);
            }
            nextNode.setTraceMetrics(element.getTraceMetrics());
            nextNode.incrementSampleCount(1);
            lastMatchedNode.addChildNode(nextNode);
            lastMatchedNode = nextNode;
        }
    }

    /*@Pure*/
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("unmergedStackTraces", unmergedStackTraces)
                .add("unmergedStackTraceThreadStates", unmergedStackTraceThreadStates)
                .add("syntheticRootNode", syntheticRootNode)
                .toString();
    }

    // recreate the stack trace as it would have been without the synthetic $trace$metric$ methods
    public static List<StackTraceElementPlus> stripSyntheticMetricMethods(
            List<StackTraceElement> stackTrace) {

        List<StackTraceElementPlus> stackTracePlus = Lists.newArrayListWithCapacity(
                stackTrace.size());
        for (Iterator<StackTraceElement> i = stackTrace.iterator(); i.hasNext();) {
            StackTraceElement element = i.next();
            String traceMetric = getTraceMetric(element);
            if (traceMetric != null) {
                String originalMethodName = element.getMethodName();
                List<String> traceMetrics = Lists.newArrayListWithCapacity(2);
                traceMetrics.add(traceMetric);
                // skip over successive $trace$metric$ methods up to and including the "original"
                // method
                while (i.hasNext()) {
                    StackTraceElement skipElement = i.next();
                    traceMetric = getTraceMetric(skipElement);
                    if (traceMetric == null) {
                        // loop should always terminate here since synthetic $trace$metric$ methods
                        // should never be the last element (the last element is the first element
                        // in the call stack)
                        originalMethodName = skipElement.getMethodName();
                        break;
                    }
                    traceMetrics.add(traceMetric);
                }
                // "original" in the sense that this is what it would have been without the
                // synthetic $trace$metric$ methods
                StackTraceElement originalElement = new StackTraceElement(element.getClassName(),
                        originalMethodName, element.getFileName(), element.getLineNumber());
                stackTracePlus.add(new StackTraceElementPlus(originalElement, traceMetrics));
            } else {
                stackTracePlus.add(new StackTraceElementPlus(element, ImmutableList.<String>of()));
            }
        }
        return stackTracePlus;
    }

    @Nullable
    private static String getTraceMetric(StackTraceElement stackTraceElement) {
        Matcher matcher = metricMarkerMethodPattern.matcher(stackTraceElement.getMethodName());
        if (matcher.matches()) {
            String group = matcher.group(1);
            checkNotNull(group);
            return group.replace("$", " ");
        } else {
            return null;
        }
    }

    private static boolean matches(StackTraceElement stackTraceElement,
            ProfileNode childNode, boolean leaf, State threadState) {

        State leafThreadState = childNode.getLeafThreadState();
        if (leafThreadState != null && leaf) {
            // only consider thread state when matching the leaf node
            return stackTraceElement.equals(childNode.getStackTraceElement())
                    && leafThreadState.equals(threadState);
        } else {
            return leafThreadState == null && !leaf
                    && stackTraceElement.equals(childNode.getStackTraceElement());
        }
    }

    public static class StackTraceElementPlus {
        private final StackTraceElement stackTraceElement;
        private final ImmutableList<String> traceMetrics;
        private StackTraceElementPlus(StackTraceElement stackTraceElement,
                List<String> traceMetrics) {
            this.stackTraceElement = stackTraceElement;
            this.traceMetrics = ImmutableList.copyOf(traceMetrics);
        }
        public StackTraceElement getStackTraceElement() {
            return stackTraceElement;
        }
        private ImmutableList<String> getTraceMetrics() {
            return traceMetrics;
        }
    }
}
