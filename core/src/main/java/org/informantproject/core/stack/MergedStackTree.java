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
package org.informantproject.core.stack;

import java.lang.Thread.State;
import java.lang.management.ThreadInfo;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

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
// TODO it would be more efficient to store stack traces unmerged up until some point
// (e.g. to optimize for trace captures which are never persisted)
// in this case, it should be configurable how many stack traces to store unmerged
// after which the existing stack traces are merged as well as future stack traces
public class MergedStackTree {

    private final Collection<MergedStackTreeNode> rootNodes =
            new ConcurrentLinkedQueue<MergedStackTreeNode>();

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
            addToStackTree(threadInfo.getStackTrace(), threadInfo.getThreadState());
        }
    }

    // public for unit tests (otherwise could be private)
    public void addToStackTree(StackTraceElement[] stackTraceElements, State threadState) {
        MergedStackTreeNode lastMatchedNode = null;
        Iterable<MergedStackTreeNode> nextChildNodes = rootNodes;
        int nextIndex;
        // navigate the stack tree nodes
        // matching the new stack trace as far as possible
        for (nextIndex = stackTraceElements.length - 1; nextIndex >= 0; nextIndex--) {
            // check all child nodes
            boolean matchFound = false;
            for (MergedStackTreeNode childNode : nextChildNodes) {
                if (matches(stackTraceElements[nextIndex], childNode, nextIndex == 0,
                        threadState)) {
                    // match found, update lastMatchedNode and continue
                    childNode.incrementSampleCount();
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
            MergedStackTreeNode nextNode = MergedStackTreeNode.create(stackTraceElements[i]);
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
}
