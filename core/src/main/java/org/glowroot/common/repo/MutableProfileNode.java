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
package org.glowroot.common.repo;

import java.io.IOException;
import java.io.StringReader;
import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;

import org.glowroot.collector.spi.ProfileNode;
import org.glowroot.common.repo.helper.JsonMarshaller;
import org.glowroot.common.repo.helper.JsonUnmarshaller;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class MutableProfileNode extends AbstractCollection<MutableProfileNode>
        implements ProfileNode {

    // null for synthetic root only
    private final @Nullable StackTraceElement stackTraceElement;

    private final @Nullable String leafThreadState;
    private long sampleCount;

    // using List over Set in order to preserve ordering
    // may contain duplicates (common from weaving groups of overloaded methods), these are filtered
    // out later when profile is written to json
    //
    // lazy instantiate list to save memory since it is usually empty
    private @Nullable ImmutableList<String> timerNames;

    // nodes mostly have a single child node, so to minimize memory consumption,
    // childNodes can either be single ProfileNode or List<ProfileNode>
    //
    // important: it can be a single-valued or empty list if truncating occurred which removed small
    // leafs
    private @Nullable Object childNodes;

    // this is only used when sending profiles to the UI (it is not used when storing)
    private int ellipsedSampleCount;

    // this is only used temporarily while filtering a profile
    private boolean matched;

    public static MutableProfileNode createSyntheticRootNode() {
        return new MutableProfileNode(null, null);
    }

    public static MutableProfileNode create(StackTraceElement stackTraceElement,
            @Nullable String leafThreadState) {
        return new MutableProfileNode(stackTraceElement, leafThreadState);
    }

    public MutableProfileNode(@Nullable StackTraceElement stackTraceElement,
            @Nullable String leafThreadState) {
        this.stackTraceElement = stackTraceElement;
        this.leafThreadState = leafThreadState;
    }

    @SuppressWarnings("unchecked")
    public void addChildNode(MutableProfileNode node) {
        if (childNodes == null) {
            childNodes = node;
        } else if (childNodes instanceof MutableProfileNode) {
            List<MutableProfileNode> list = Lists.newArrayListWithCapacity(2);
            list.add((MutableProfileNode) checkNotNull(childNodes));
            list.add(node);
            childNodes = list;
        } else {
            ((List<MutableProfileNode>) childNodes).add(node);
        }
    }

    // may contain duplicates
    public void setTimerNames(ImmutableList<String> timerNames) {
        this.timerNames = timerNames;
    }

    // sampleCount is volatile to ensure visibility, but this method still needs to be called under
    // an appropriate lock so that two threads do not try to increment the count at the same time
    public void incrementSampleCount(long num) {
        sampleCount += num;
    }

    public void incrementEllipsedSampleCount(int sampleCount) {
        ellipsedSampleCount += sampleCount;
    }

    public void setMatched() {
        matched = true;
    }

    public void resetMatched() {
        matched = false;
    }

    public void setSampleCount(long sampleCount) {
        this.sampleCount = sampleCount;
    }

    // only returns null for synthetic root
    @Override
    public @Nullable StackTraceElement stackTraceElement() {
        return stackTraceElement;
    }

    public boolean isSyntheticRootNode() {
        return stackTraceElement == null;
    }

    @Override
    public @Nullable String leafThreadState() {
        return leafThreadState;
    }

    @Override
    public long sampleCount() {
        return sampleCount;
    }

    // may contain duplicates
    @Override
    public ImmutableList<String> timerNames() {
        return timerNames == null ? ImmutableList.<String>of() : timerNames;
    }

    // this method only exists to make the code clearer in places where the node is being used as an
    // iterable
    @Override
    public Collection<MutableProfileNode> childNodes() {
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public int size() {
        if (childNodes == null) {
            return 0;
        } else if (childNodes instanceof MutableProfileNode) {
            return 1;
        } else {
            return ((List<MutableProfileNode>) childNodes).size();
        }
    }

    public MutableProfileNode copy() throws IOException {
        // TODO optimize, but be careful not to use recursive algorithm due to tree depth leading
        // to StackOverflowError
        StringReader reader = new StringReader(JsonMarshaller.marshal(this));
        try {
            return JsonUnmarshaller.unmarshalProfile(reader);
        } finally {
            reader.close();
        }
    }

    // return value supports Iterator.remove() for use in truncating small leafs
    @Override
    @SuppressWarnings("unchecked")
    public Iterator<MutableProfileNode> iterator() {
        final Object childNodes = this.childNodes;
        if (childNodes == null) {
            return ImmutableList.<MutableProfileNode>of().iterator();
        } else if (childNodes instanceof MutableProfileNode) {
            return new Iterator<MutableProfileNode>() {
                private boolean done;
                @Override
                public boolean hasNext() {
                    return !done;
                }
                @Override
                public MutableProfileNode next() {
                    if (done) {
                        throw new NoSuchElementException();
                    }
                    done = true;
                    return (MutableProfileNode) checkNotNull(childNodes);
                }
                @Override
                public void remove() {
                    MutableProfileNode.this.childNodes = null;
                }
            };
        } else {
            return ((List<MutableProfileNode>) childNodes).iterator();
        }
    }

    @EnsuresNonNullIf(expression = "childNodes", result = true)
    @SuppressWarnings("unchecked")
    public boolean hasOneChildNode() {
        if (childNodes == null) {
            return false;
        } else if (childNodes instanceof MutableProfileNode) {
            return true;
        } else {
            return ((List<MutableProfileNode>) childNodes).size() == 1;
        }
    }

    @RequiresNonNull("childNodes")
    @SuppressWarnings("unchecked")
    public MutableProfileNode getOnlyChildNode() {
        if (childNodes instanceof MutableProfileNode) {
            return (MutableProfileNode) childNodes;
        } else {
            return ((List<MutableProfileNode>) childNodes).get(0);
        }
    }

    public int getEllipsedSampleCount() {
        return ellipsedSampleCount;
    }

    public boolean isMatched() {
        return matched;
    }

    public void mergeMatchedNode(MutableProfileNode anotherSyntheticRootNode) {
        // can only be called on synthetic root node
        checkState(stackTraceElement == null);
        merge(this, anotherSyntheticRootNode);
    }

    // merge the right side into the left side
    private static void merge(MutableProfileNode leftRootNode, MutableProfileNode rightRootNode) {
        Deque<MatchedNodePair> stack = new ArrayDeque<MatchedNodePair>();
        stack.add(ImmutableMatchedNodePair.of(leftRootNode, rightRootNode));
        while (!stack.isEmpty()) {
            MatchedNodePair matchedPair = stack.pop();
            MutableProfileNode leftNode = matchedPair.leftNode();
            MutableProfileNode rightNode = matchedPair.rightNode();
            mergeNodeShallow(leftNode, rightNode);
            for (MutableProfileNode rightChildNode : rightNode.childNodes()) {
                MutableProfileNode matchingLeftChildNode =
                        findMatch(leftNode.childNodes(), rightChildNode);
                if (matchingLeftChildNode == null) {
                    leftNode.addChildNode(rightChildNode);
                } else {
                    stack.push(ImmutableMatchedNodePair.of(matchingLeftChildNode, rightChildNode));
                }
            }
        }
    }

    private static @Nullable MutableProfileNode findMatch(
            Iterable<MutableProfileNode> leftChildNodes, MutableProfileNode rightChildNode) {
        for (MutableProfileNode leftChildNode : leftChildNodes) {
            if (matches(leftChildNode, rightChildNode)) {
                return leftChildNode;
            }
        }
        return null;
    }

    // merge the right side into the left side
    private static void mergeNodeShallow(MutableProfileNode leftNode,
            MutableProfileNode rightNode) {
        leftNode.incrementSampleCount(rightNode.sampleCount());
        // the timer names for a given stack element should always match, unless the line
        // numbers aren't available and overloaded methods are matched up, or the stack
        // trace was captured while one of the synthetic $glowroot$timer$ methods was
        // executing in which case one of the timer names may be a subset of the other,
        // in which case, the superset wins:
        ImmutableList<String> timerNames = rightNode.timerNames();
        if (timerNames.size() > leftNode.timerNames().size()) {
            leftNode.setTimerNames(timerNames);
        }
    }

    public static boolean matches(MutableProfileNode leftNode, MutableProfileNode rightNode) {
        if (!Objects.equal(leftNode.leafThreadState, rightNode.leafThreadState)) {
            return false;
        }
        return Objects.equal(leftNode.stackTraceElement, rightNode.stackTraceElement);
    }

    @Value.Immutable
    @Styles.AllParameters
    interface MatchedNodePair {
        MutableProfileNode leftNode();
        MutableProfileNode rightNode();
    }
}
