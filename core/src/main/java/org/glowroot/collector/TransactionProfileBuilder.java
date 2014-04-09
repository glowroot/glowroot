/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.collector;

import java.util.List;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Objects;

import org.glowroot.trace.model.MergedStackTree;
import org.glowroot.trace.model.MergedStackTreeNode;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TransactionProfileBuilder {

    private final Object lock = new Object();
    @GuardedBy("lock")
    private final MergedStackTreeNode syntheticRootNode = MergedStackTreeNode.createSyntheticRoot();

    Object getLock() {
        return lock;
    }

    // must be holding lock to call and can only use resulting node tree inside the same
    // synchronized block
    MergedStackTreeNode getSyntheticRootNode() {
        return syntheticRootNode;
    }

    void addProfile(MergedStackTree profile) {
        synchronized (lock) {
            synchronized (profile.getLock()) {
                mergeNode(syntheticRootNode, profile.getSyntheticRootNode());
            }
        }
    }

    private void mergeNode(MergedStackTreeNode node, MergedStackTreeNode toBeMergedNode) {
        node.incrementSampleCount(toBeMergedNode.getSampleCount());
        // the metric names for a given stack element should always match, unless
        // the line numbers aren't available and overloaded methods are matched up, or
        // the stack trace was captured while one of the synthetic $metric$ methods was
        // executing in which case one of the metric names may be a subset of the other,
        // in which case, the superset wins:
        List<String> metricNames = toBeMergedNode.getMetricNames();
        if (metricNames != null
                && metricNames.size() > node.getMetricNames().size()) {
            node.setMetricNames(metricNames);
        }
        for (MergedStackTreeNode toBeMergedChildNode : toBeMergedNode.getChildNodes()) {
            // for each to-be-merged child node look for a match
            MergedStackTreeNode foundMatchingChildNode = null;
            for (MergedStackTreeNode childNode : node.getChildNodes()) {
                if (matches(toBeMergedChildNode, childNode)) {
                    foundMatchingChildNode = childNode;
                    break;
                }
            }
            if (foundMatchingChildNode == null) {
                // since to-be-merged nodes may still be used when storing the trace, need to make
                // copy here
                StackTraceElement stackTraceElement = toBeMergedChildNode.getStackTraceElement();
                // stackTraceElement is only null for synthetic root
                checkNotNull(stackTraceElement);
                foundMatchingChildNode = MergedStackTreeNode.create(stackTraceElement,
                        toBeMergedChildNode.getLeafThreadState());
                node.addChildNode(foundMatchingChildNode);
            }
            mergeNode(foundMatchingChildNode, toBeMergedChildNode);
        }
    }

    private boolean matches(MergedStackTreeNode node1, MergedStackTreeNode node2) {
        return Objects.equal(node1.getStackTraceElement(), node2.getStackTraceElement())
                && Objects.equal(node1.getLeafThreadState(), node2.getLeafThreadState());
    }

    /*@Pure*/
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("syntheticRootNode", syntheticRootNode)
                .toString();
    }
}
