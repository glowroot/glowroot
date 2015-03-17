/*
 * Copyright 2011-2015 the original author or authors.
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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import static com.google.common.base.Preconditions.checkNotNull;

public class Profile {

    private static final Pattern timerMarkerMethodPattern =
            Pattern.compile("^.*\\$glowroot\\$timer\\$(.*)\\$[0-9]+$");

    private final Object lock = new Object();
    // optimized for trace captures which are never read
    @GuardedBy("lock")
    private final List<List<StackTraceElement>> unmergedStackTraces = Lists.newArrayList();
    @GuardedBy("lock")
    private final List<State> unmergedStackTraceThreadStates = Lists.newArrayList();
    @GuardedBy("lock")
    private final ProfileNode syntheticRootNode = ProfileNode.createSyntheticRoot();

    private final boolean mayHaveSyntheticTimerMethods;

    @VisibleForTesting
    public Profile(boolean mayHaveSyntheticTimerMethods) {
        this.mayHaveSyntheticTimerMethods = mayHaveSyntheticTimerMethods;
    }

    public Object getLock() {
        return lock;
    }

    // must be holding lock to call and can only use resulting node tree inside the same
    // synchronized block
    public ProfileNode getSyntheticRootNode() {
        mergeTheUnmergedStackTraces();
        return syntheticRootNode;
    }

    public int getSampleCount() {
        synchronized (lock) {
            return syntheticRootNode.getSampleCount() + unmergedStackTraces.size();
        }
    }

    public boolean mayHaveSyntheticTimerMethods() {
        return mayHaveSyntheticTimerMethods;
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
            if (mayHaveSyntheticTimerMethods) {
                addToStackTree(stripSyntheticTimerMethods(stackTrace), threadState);
            } else {
                // fast path for common case
                addToStackTree(stackTrace, threadState);
            }
        }
        unmergedStackTraces.clear();
        unmergedStackTraceThreadStates.clear();
    }

    // must be holding lock to call
    @VisibleForTesting
    public void addToStackTree(List<?> stackTrace, State threadState) {
        syntheticRootNode.incrementSampleCount(1);
        ProfileNode lastMatchedNode = syntheticRootNode;
        List<ProfileNode> nextChildNodes = syntheticRootNode.getChildNodes();
        int nextIndex;
        // navigate the stack tree nodes
        // matching the new stack trace as far as possible
        for (nextIndex = stackTrace.size() - 1; nextIndex >= 0; nextIndex--) {
            Object element = stackTrace.get(nextIndex);
            // look for matching node
            ProfileNode matchingNode = null;
            for (ProfileNode childNode : nextChildNodes) {
                if (matches(getStackTraceElement(element), childNode, nextIndex == 0,
                        threadState)) {
                    matchingNode = childNode;
                    break;
                }
            }
            if (matchingNode == null) {
                break;
            }
            // match found, update lastMatchedNode and break out of the inner loop
            matchingNode.incrementSampleCount(1);
            // the timer names for a given stack element should always match, unless
            // the line numbers aren't available and overloaded methods are matched up, or
            // the stack trace was captured while one of the synthetic $glowroot$timer$
            // methods was executing in which case one of the timer names may be a
            // subset of the other, in which case, the superset wins:
            List<String> timerNames = getTimerNames(element);
            if (timerNames.size() > matchingNode.getTimerNames().size()) {
                matchingNode.setTimerNames(timerNames);
            }
            lastMatchedNode = matchingNode;
            nextChildNodes = lastMatchedNode.getChildNodes();
        }
        // add remaining stack trace elements
        for (int i = nextIndex; i >= 0; i--) {
            Object element = stackTrace.get(i);
            ProfileNode nextNode;
            if (i == 0) {
                // leaf node
                nextNode = ProfileNode.create(getStackTraceElement(element), threadState);
            } else {
                nextNode = ProfileNode.create(getStackTraceElement(element), null);
            }
            nextNode.setTimerNames(getTimerNames(element));
            nextNode.incrementSampleCount(1);
            lastMatchedNode.addChildNode(nextNode);
            lastMatchedNode = nextNode;
        }
    }

    // recreate the stack trace as it would have been without the synthetic $glowroot$timer$
    // methods
    public static List<StackTraceElementPlus> stripSyntheticTimerMethods(
            List<StackTraceElement> stackTrace) {

        List<StackTraceElementPlus> stackTracePlus =
                Lists.newArrayListWithCapacity(stackTrace.size());
        for (Iterator<StackTraceElement> i = stackTrace.iterator(); i.hasNext();) {
            StackTraceElement element = i.next();
            String timerName = getTimerName(element);
            if (timerName == null) {
                stackTracePlus.add(new StackTraceElementPlus(element, ImmutableList.<String>of()));
                continue;
            }
            String originalMethodName = element.getMethodName();
            List<String> timerNames = Lists.newArrayListWithCapacity(2);
            timerNames.add(timerName);
            // skip over successive $glowroot$timer$ methods up to and including the "original"
            // method
            while (i.hasNext()) {
                StackTraceElement skipElement = i.next();
                timerName = getTimerName(skipElement);
                if (timerName == null) {
                    // loop should always terminate here since synthetic $glowroot$timer$
                    // methods should never be the last element (the last element is the first
                    // element in the call stack)
                    originalMethodName = skipElement.getMethodName();
                    break;
                }
                timerNames.add(timerName);
            }
            // "original" in the sense that this is what it would have been without the
            // synthetic $timer$ methods
            StackTraceElement originalElement = new StackTraceElement(element.getClassName(),
                    originalMethodName, element.getFileName(), element.getLineNumber());
            stackTracePlus.add(new StackTraceElementPlus(originalElement, timerNames));
        }
        return stackTracePlus;
    }

    private static @Nullable String getTimerName(StackTraceElement stackTraceElement) {
        String methodName = stackTraceElement.getMethodName();
        if (!methodName.contains("$glowroot$timer$")) {
            // fast contains check for common case
            return null;
        }
        Matcher matcher = timerMarkerMethodPattern.matcher(methodName);
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

    private static StackTraceElement getStackTraceElement(Object stackTraceElementOrPlus) {
        if (stackTraceElementOrPlus instanceof StackTraceElement) {
            return (StackTraceElement) stackTraceElementOrPlus;
        } else {
            return ((StackTraceElementPlus) stackTraceElementOrPlus).getStackTraceElement();
        }
    }

    private static List<String> getTimerNames(Object stackTraceElementOrPlus) {
        if (stackTraceElementOrPlus instanceof StackTraceElement) {
            return ImmutableList.of();
        } else {
            return ((StackTraceElementPlus) stackTraceElementOrPlus).getTimerNames();
        }
    }

    public static class StackTraceElementPlus {
        private final StackTraceElement stackTraceElement;
        private final ImmutableList<String> timerNames;
        private StackTraceElementPlus(StackTraceElement stackTraceElement,
                List<String> timerNames) {
            this.stackTraceElement = stackTraceElement;
            this.timerNames = ImmutableList.copyOf(timerNames);
        }
        public StackTraceElement getStackTraceElement() {
            return stackTraceElement;
        }
        private ImmutableList<String> getTimerNames() {
            return timerNames;
        }
    }
}
