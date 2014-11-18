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
package org.glowroot.transaction.model;

import java.lang.Thread.State;
import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import static com.google.common.base.Preconditions.checkNotNull;

public class Profile {

    private static final Pattern metricMarkerMethodPattern =
            Pattern.compile("^.*\\$glowroot\\$metric\\$(.*)\\$[0-9]+$");

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

    // limit is just to cap memory consumption for a single transaction profile in case it runs for
    // a very very very long time
    void addStackTrace(ThreadInfo threadInfo, int limit) {
        synchronized (lock) {
            if (syntheticRootNode.getSampleCount() + unmergedStackTraces.size() >= limit) {
                return;
            }
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
                    // the stack trace was captured while one of the synthetic $glowroot$metric$
                    // methods was executing in which case one of the metric names may be a
                    // subset of the other, in which case, the superset wins:
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
            ProfileNode nextNode;
            if (i == 0) {
                // leaf node
                nextNode = ProfileNode.create(element.getStackTraceElement(), threadState);
            } else {
                nextNode = ProfileNode.create(element.getStackTraceElement(), null);
            }
            nextNode.setMetricNames(element.getMetricNames());
            nextNode.incrementSampleCount(1);
            lastMatchedNode.addChildNode(nextNode);
            lastMatchedNode = nextNode;
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("unmergedStackTraces", unmergedStackTraces)
                .add("unmergedStackTraceThreadStates", unmergedStackTraceThreadStates)
                .add("syntheticRootNode", syntheticRootNode)
                .toString();
    }

    // recreate the stack trace as it would have been without the synthetic $glowroot$metric$
    // methods
    public static List<StackTraceElementPlus> stripSyntheticMetricMethods(
            List<StackTraceElement> stackTrace) {

        List<StackTraceElementPlus> stackTracePlus =
                Lists.newArrayListWithCapacity(stackTrace.size());
        for (Iterator<StackTraceElement> i = stackTrace.iterator(); i.hasNext();) {
            StackTraceElement element = i.next();
            String metricName = getMetricName(element);
            if (metricName != null) {
                String originalMethodName = element.getMethodName();
                List<String> metricNames = Lists.newArrayListWithCapacity(2);
                metricNames.add(metricName);
                // skip over successive $glowroot$metric$ methods up to and including the "original"
                // method
                while (i.hasNext()) {
                    StackTraceElement skipElement = i.next();
                    metricName = getMetricName(skipElement);
                    if (metricName == null) {
                        // loop should always terminate here since synthetic $glowroot$metric$
                        // methods should never be the last element (the last element is the first
                        // element in the call stack)
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
                stackTracePlus.add(new StackTraceElementPlus(element, ImmutableList.<String>of()));
            }
        }
        return stackTracePlus;
    }

    private static @Nullable String getMetricName(StackTraceElement stackTraceElement) {
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
        private final ImmutableList<String> metricNames;
        private StackTraceElementPlus(StackTraceElement stackTraceElement,
                List<String> metricNames) {
            this.stackTraceElement = stackTraceElement;
            this.metricNames = ImmutableList.copyOf(metricNames);
        }
        public StackTraceElement getStackTraceElement() {
            return stackTraceElement;
        }
        private ImmutableList<String> getMetricNames() {
            return metricNames;
        }
    }
}
