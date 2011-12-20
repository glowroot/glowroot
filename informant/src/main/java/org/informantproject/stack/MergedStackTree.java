/**
 * Copyright 2011 the original author or authors.
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
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    // this method returns an iterable with a "weakly consistent" iterator
    // that will never throw ConcurrentModificationException, see
    // ConcurrentLinkedQueue.iterator()
    public Iterable<MergedStackTreeNode> getRootNodes() {
        return rootNodes;
    }

    public void captureStackTrace(Thread thread) {
        // the scope of this lock could be reduced considerably, but it probably only makes sense to
        // capture and build a single stack trace at a time anyways
        synchronized (lock) {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            ThreadInfo threadInfo = threadBean.getThreadInfo(thread.getId(), Integer.MAX_VALUE);
            addToStackTree(threadInfo.getStackTrace(), threadInfo.getThreadState());
        }
    }

    private void addToStackTree(StackTraceElement[] stackTraceElements, State threadState) {
        MergedStackTreeNode lastMatchedNode = null;
        Iterable<MergedStackTreeNode> nextChildNodes = rootNodes;
        int nextStackTraceIndex = 0;
        // navigate the stack tree nodes
        // matching the new stack trace as far as possible
        for (nextStackTraceIndex = stackTraceElements.length - 1; nextStackTraceIndex >= 0; nextStackTraceIndex--) {
            // check all child nodes
            boolean matchFound = false;
            for (MergedStackTreeNode childNode : nextChildNodes) {
                if (stackTraceElements[nextStackTraceIndex]
                        .equals(childNode.getStackTraceElement())) {
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
        for (int i = nextStackTraceIndex; i >= 0; i--) {
            MergedStackTreeNode nextNode = new MergedStackTreeNode(stackTraceElements[i]);
            if (lastMatchedNode == null) {
                // new root node
                rootNodes.add(nextNode);
            } else {
                lastMatchedNode.addChildNode(nextNode);
            }
            lastMatchedNode = nextNode;
        }
        // add leaf sampling
        lastMatchedNode.addLeafSampling(threadState);
    }
}
