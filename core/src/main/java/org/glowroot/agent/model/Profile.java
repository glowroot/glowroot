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
package org.glowroot.agent.model;

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
import org.immutables.value.Value;

import org.glowroot.common.repo.MutableProfileNode;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;

public class Profile {

    private static final Pattern timerMarkerMethodPattern =
            Pattern.compile("^.*\\$glowroot\\$timer\\$(.*)\\$[0-9]+$");

    private final Object lock = new Object();
    // optimized for trace captures which are never read
    @GuardedBy("lock")
    private final List<List<StackTraceElement>> unmergedStackTraces = Lists.newArrayList();
    @GuardedBy("lock")
    private final List<String> unmergedStackTraceThreadStates = Lists.newArrayList();
    @GuardedBy("lock")
    private final MutableProfileNode syntheticRootNode =
            MutableProfileNode.createSyntheticRootNode();

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
    @GuardedBy("lock")
    public MutableProfileNode getSyntheticRootNode() {
        mergeTheUnmergedStackTraces();
        return syntheticRootNode;
    }

    public long getSampleCount() {
        synchronized (lock) {
            return syntheticRootNode.sampleCount() + unmergedStackTraces.size();
        }
    }

    public boolean mayHaveSyntheticTimerMethods() {
        return mayHaveSyntheticTimerMethods;
    }

    // limit is just to cap memory consumption for a single transaction profile in case it runs for
    // a very very very long time
    void addStackTrace(ThreadInfo threadInfo, int limit) {
        synchronized (lock) {
            if (syntheticRootNode.sampleCount() + unmergedStackTraces.size() >= limit) {
                return;
            }
            List<StackTraceElement> stackTrace = Arrays.asList(threadInfo.getStackTrace());
            unmergedStackTraces.add(stackTrace);
            unmergedStackTraceThreadStates.add(threadInfo.getThreadState().name());
            if (unmergedStackTraces.size() >= 10) {
                // merged stack tree takes up less memory, so merge from time to time
                mergeTheUnmergedStackTraces();
            }
        }
    }

    @GuardedBy("lock")
    private void mergeTheUnmergedStackTraces() {
        for (int i = 0; i < unmergedStackTraces.size(); i++) {
            List<StackTraceElement> stackTrace = unmergedStackTraces.get(i);
            String threadState = unmergedStackTraceThreadStates.get(i);
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

    @GuardedBy("lock")
    @VisibleForTesting
    public void addToStackTree(List<? extends /*@NonNull*/Object> stackTrace, String threadState) {
        syntheticRootNode.incrementSampleCount(1);
        MutableProfileNode lastMatchedNode = syntheticRootNode;
        Iterable<MutableProfileNode> nextChildNodes = syntheticRootNode.childNodes();
        int nextIndex;
        // navigate the stack tree nodes
        // matching the new stack trace as far as possible
        for (nextIndex = stackTrace.size() - 1; nextIndex >= 0; nextIndex--) {
            Object element = stackTrace.get(nextIndex);
            // look for matching node
            MutableProfileNode matchingNode = null;
            for (MutableProfileNode childNode : nextChildNodes) {
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
            ImmutableList<String> timerNames = getTimerNames(element);
            if (timerNames.size() > matchingNode.timerNames().size()) {
                matchingNode.setTimerNames(timerNames);
            }
            lastMatchedNode = matchingNode;
            nextChildNodes = lastMatchedNode.childNodes();
        }
        // add remaining stack trace elements
        for (int i = nextIndex; i >= 0; i--) {
            Object element = stackTrace.get(i);
            MutableProfileNode nextNode;
            StackTraceElement stackTraceElement = getStackTraceElement(element);
            if (i == 0) {
                // leaf node
                nextNode = MutableProfileNode.create(stackTraceElement, threadState);
            } else {
                nextNode = MutableProfileNode.create(stackTraceElement, null);
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
            String originalMethodName = element.getMethodName();
            if (originalMethodName == null) {
                // methodName can be null after hotswapping under Eclipse debugger
                continue;
            }
            String timerName = getTimerName(originalMethodName);
            if (timerName == null) {
                stackTracePlus.add(
                        ImmutableStackTraceElementPlus.of(element, ImmutableList.<String>of()));
                continue;
            }
            List<String> timerNames = Lists.newArrayListWithCapacity(2);
            timerNames.add(timerName);
            // skip over successive $glowroot$timer$ methods up to and including the "original"
            // method
            while (i.hasNext()) {
                StackTraceElement skipElement = i.next();
                String skipMethodName = skipElement.getMethodName();
                if (skipMethodName == null) {
                    // methodName can be null after hotswapping under Eclipse debugger
                    continue;
                }
                timerName = getTimerName(skipMethodName);
                if (timerName == null) {
                    // loop should always terminate here since synthetic $glowroot$timer$
                    // methods should never be the last element (the last element is the first
                    // element in the call stack)
                    originalMethodName = skipMethodName;
                    break;
                }
                timerNames.add(timerName);
            }
            // "original" in the sense that this is what it would have been without the
            // synthetic $timer$ methods
            StackTraceElement originalElement = new StackTraceElement(element.getClassName(),
                    originalMethodName, element.getFileName(), element.getLineNumber());
            stackTracePlus.add(ImmutableStackTraceElementPlus.of(originalElement, timerNames));
        }
        return stackTracePlus;
    }

    private static @Nullable String getTimerName(String methodName) {
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
            MutableProfileNode childNode, boolean leaf, String threadState) {
        String leafThreadState = childNode.leafThreadState();
        if (leafThreadState != null && leaf) {
            // only consider thread state when matching the leaf node
            return stackTraceElement.equals(childNode.stackTraceElement())
                    && leafThreadState.equals(threadState);
        } else {
            return leafThreadState == null && !leaf
                    && stackTraceElement.equals(childNode.stackTraceElement());
        }
    }

    private static StackTraceElement getStackTraceElement(Object stackTraceElementOrPlus) {
        StackTraceElement stackTraceElement;
        if (stackTraceElementOrPlus instanceof StackTraceElement) {
            stackTraceElement = (StackTraceElement) stackTraceElementOrPlus;
        } else {
            stackTraceElement =
                    ((StackTraceElementPlus) stackTraceElementOrPlus).stackTraceElement();
        }
        return stackTraceElement;
    }

    private static ImmutableList<String> getTimerNames(Object stackTraceElementOrPlus) {
        if (stackTraceElementOrPlus instanceof StackTraceElement) {
            return ImmutableList.of();
        } else {
            return ((StackTraceElementPlus) stackTraceElementOrPlus).timerNames();
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface StackTraceElementPlus {
        StackTraceElement stackTraceElement();
        ImmutableList<String> timerNames();
    }
}
