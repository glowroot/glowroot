/*
 * Copyright 2014-2015 the original author or authors.
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

import com.google.common.collect.ImmutableList;

import org.glowroot.common.repo.MutableProfileNode;

import static com.google.common.base.Preconditions.checkNotNull;

// must be used under an appropriate lock
class AggregateProfileBuilder {

    private final MutableProfileNode syntheticRootNode =
            MutableProfileNode.createSyntheticRootNode();

    MutableProfileNode getSyntheticRootNode() {
        return syntheticRootNode;
    }

    void addProfile(Profile profile) {
        synchronized (profile.getLock()) {
            mergeNode(syntheticRootNode, profile.getSyntheticRootNode(),
                    profile.mayHaveSyntheticTimerMethods());
        }
    }

    private void mergeNode(MutableProfileNode node, MutableProfileNode toBeMergedNode,
            boolean mayHaveSyntheticTimerMethods) {
        node.incrementSampleCount(toBeMergedNode.sampleCount());
        if (mayHaveSyntheticTimerMethods) {
            // the timer names for a given stack element should always match, unless
            // the line numbers aren't available and overloaded methods are matched up, or
            // the stack trace was captured while one of the synthetic $glowroot$timer$ methods was
            // executing in which case one of the timer names may be a subset of the other,
            // in which case, the superset wins:
            ImmutableList<String> timerNames = toBeMergedNode.timerNames();
            if (timerNames.size() > node.timerNames().size()) {
                node.setTimerNames(timerNames);
            }
        }
        for (MutableProfileNode toBeMergedChildNode : toBeMergedNode.childNodes()) {
            // for each to-be-merged child node look for a match
            MutableProfileNode foundMatchingChildNode = null;
            for (MutableProfileNode childNode : node.childNodes()) {
                if (MutableProfileNode.matches(toBeMergedChildNode, childNode)) {
                    foundMatchingChildNode = childNode;
                    break;
                }
            }
            if (foundMatchingChildNode == null) {
                // since to-be-merged nodes may still be used when storing the trace, need to make
                // copy here
                StackTraceElement stackTraceElement = toBeMergedChildNode.stackTraceElement();
                // stackTraceElement is only null for synthetic root
                checkNotNull(stackTraceElement);
                foundMatchingChildNode = MutableProfileNode.create(stackTraceElement,
                        toBeMergedChildNode.leafThreadState());
                node.addChildNode(foundMatchingChildNode);
            }
            mergeNode(foundMatchingChildNode, toBeMergedChildNode, mayHaveSyntheticTimerMethods);
        }
    }
}
