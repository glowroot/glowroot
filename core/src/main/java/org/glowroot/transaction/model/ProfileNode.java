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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;

import org.glowroot.common.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

@JsonSerialize(using = ProfileNode.Serializer.class)
@JsonDeserialize(using = ProfileNode.Deserializer.class)
public class ProfileNode implements Iterable<ProfileNode> {

    // the type is StackTraceElement when building profiles, but is String when reading profiles
    // from store to avoid unnecessary parsing and creation of StackTraceElement
    //
    // null for synthetic root only
    private final @Nullable Object stackTraceElementObj;

    private final @Nullable String leafThreadState;
    private int sampleCount;

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
    private boolean ellipsed;

    public static ProfileNode createSyntheticRoot() {
        return new ProfileNode(null, null);
    }

    public static ProfileNode create(Object stackTraceElementObj,
            @Nullable String leafThreadState) {
        return new ProfileNode(stackTraceElementObj, leafThreadState);
    }

    private ProfileNode(@Nullable Object stackTraceElementObj,
            @Nullable String leafThreadState) {
        this.stackTraceElementObj = stackTraceElementObj;
        this.leafThreadState = leafThreadState;
    }

    @SuppressWarnings("unchecked")
    public void addChildNode(ProfileNode node) {
        if (childNodes == null) {
            childNodes = node;
        } else if (childNodes instanceof ProfileNode) {
            List<ProfileNode> list = Lists.newArrayListWithCapacity(2);
            list.add((ProfileNode) checkNotNull(childNodes));
            list.add(node);
            childNodes = list;
        } else {
            ((List<ProfileNode>) childNodes).add(node);
        }
    }

    // may contain duplicates
    public void setTimerNames(ImmutableList<String> timerNames) {
        this.timerNames = timerNames;
    }

    // sampleCount is volatile to ensure visibility, but this method still needs to be called under
    // an appropriate lock so that two threads do not try to increment the count at the same time
    public void incrementSampleCount(int num) {
        sampleCount += num;
    }

    public void setEllipsed() {
        ellipsed = true;
    }

    // only returns null for synthetic root
    public @Nullable Object getStackTraceElementObj() {
        return stackTraceElementObj;
    }

    public String getStackTraceElementStr() {
        if (stackTraceElementObj instanceof String) {
            return (String) stackTraceElementObj;
        } else if (stackTraceElementObj == null) {
            return "";
        } else {
            return stackTraceElementObj.toString();
        }
    }

    public @Nullable String getLeafThreadState() {
        return leafThreadState;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    // may contain duplicates
    public ImmutableList<String> getTimerNames() {
        return timerNames == null ? ImmutableList.<String>of() : timerNames;
    }

    // this method only exists to make the code clearer in places where the node is being used as an
    // iterable
    public Iterable<ProfileNode> getChildNodes() {
        return this;
    }

    // return value supports Iterator.remove() for use in truncating small leafs
    @Override
    @SuppressWarnings("unchecked")
    public Iterator<ProfileNode> iterator() {
        final Object childNodes = this.childNodes;
        if (childNodes == null) {
            return ImmutableList.<ProfileNode>of().iterator();
        } else if (childNodes instanceof ProfileNode) {
            return new Iterator<ProfileNode>() {
                private boolean done;
                @Override
                public boolean hasNext() {
                    return !done;
                }
                @Override
                public ProfileNode next() {
                    if (done) {
                        throw new NoSuchElementException();
                    }
                    done = true;
                    return (ProfileNode) checkNotNull(childNodes);
                }
                @Override
                public void remove() {
                    ProfileNode.this.childNodes = null;
                }
            };
        } else {
            return ((List<ProfileNode>) childNodes).iterator();
        }
    }

    @SuppressWarnings("unchecked")
    public boolean isChildNodesEmpty() {
        if (childNodes == null) {
            return true;
        } else if (childNodes instanceof ProfileNode) {
            return false;
        } else {
            return ((List<ProfileNode>) childNodes).isEmpty();
        }
    }

    @EnsuresNonNullIf(expression = "childNodes", result = true)
    @SuppressWarnings("unchecked")
    public boolean hasOneChildNode() {
        if (childNodes == null) {
            return false;
        } else if (childNodes instanceof ProfileNode) {
            return true;
        } else {
            return ((List<ProfileNode>) childNodes).size() == 1;
        }
    }

    @RequiresNonNull("childNodes")
    @SuppressWarnings("unchecked")
    public ProfileNode getOnlyChildNode() {
        if (childNodes instanceof ProfileNode) {
            return (ProfileNode) childNodes;
        } else {
            return ((List<ProfileNode>) childNodes).get(0);
        }
    }

    public boolean isEllipsed() {
        return ellipsed;
    }

    public void mergeMatchedNode(ProfileNode anotherSyntheticRootNode) {
        // can only be called on synthetic root node
        checkState(stackTraceElementObj == null);
        merge(this, anotherSyntheticRootNode);
    }

    public boolean isSameStackTraceElement(StackTraceElement stackTraceElement) {
        if (stackTraceElementObj instanceof StackTraceElement) {
            return stackTraceElementObj.equals(stackTraceElement);
        } else if (stackTraceElementObj == null) {
            return false;
        } else {
            // stackTraceElementObj is a String
            return stackTraceElementObj.equals(stackTraceElement.toString());
        }
    }

    // merge the right side into the left side
    private static void merge(ProfileNode leftRootNode, ProfileNode rightRootNode) {
        Deque<MatchedNodePair> stack = new ArrayDeque<MatchedNodePair>();
        stack.add(MatchedNodePair.of(leftRootNode, rightRootNode));
        while (!stack.isEmpty()) {
            MatchedNodePair matchedPair = stack.pop();
            ProfileNode leftNode = matchedPair.leftNode();
            ProfileNode rightNode = matchedPair.rightNode();
            mergeNodeShallow(leftNode, rightNode);
            for (ProfileNode rightChildNode : rightNode.getChildNodes()) {
                ProfileNode matchingLeftChildNode =
                        findMatch(leftNode.getChildNodes(), rightChildNode);
                if (matchingLeftChildNode == null) {
                    leftNode.addChildNode(rightChildNode);
                } else {
                    stack.push(MatchedNodePair.of(matchingLeftChildNode, rightChildNode));
                }
            }
        }
    }

    private static @Nullable ProfileNode findMatch(Iterable<ProfileNode> leftChildNodes,
            ProfileNode rightChildNode) {
        for (ProfileNode leftChildNode : leftChildNodes) {
            if (matches(leftChildNode, rightChildNode)) {
                return leftChildNode;
            }
        }
        return null;
    }

    // merge the right side into the left side
    private static void mergeNodeShallow(ProfileNode leftNode, ProfileNode rightNode) {
        leftNode.incrementSampleCount(rightNode.getSampleCount());
        // the timer names for a given stack element should always match, unless the line
        // numbers aren't available and overloaded methods are matched up, or the stack
        // trace was captured while one of the synthetic $glowroot$timer$ methods was
        // executing in which case one of the timer names may be a subset of the other,
        // in which case, the superset wins:
        ImmutableList<String> timerNames = rightNode.getTimerNames();
        if (timerNames.size() > leftNode.getTimerNames().size()) {
            leftNode.setTimerNames(timerNames);
        }
    }

    public static boolean matches(ProfileNode leftNode, ProfileNode rightNode) {
        if (!Objects.equal(leftNode.leafThreadState, rightNode.leafThreadState)) {
            return false;
        }
        if (leftNode.stackTraceElementObj instanceof StackTraceElement) {
            return rightNode.isSameStackTraceElement(
                    (StackTraceElement) leftNode.stackTraceElementObj);
        }
        if (rightNode.stackTraceElementObj instanceof StackTraceElement) {
            return leftNode.isSameStackTraceElement(
                    (StackTraceElement) rightNode.stackTraceElementObj);
        }
        // both Strings/null
        return Objects.equal(leftNode.stackTraceElementObj, rightNode.stackTraceElementObj);
    }

    // custom serializer to avoid StackOverflowError caused by default recursive algorithm
    static class Serializer extends JsonSerializer<ProfileNode> {
        @Override
        public void serialize(ProfileNode value, JsonGenerator gen,
                SerializerProvider serializers) throws IOException {
            new ProfileWriter(value, gen).write();
        }
    }

    // custom deserializer to avoid StackOverflowError caused by default recursive algorithm
    static class Deserializer extends JsonDeserializer<ProfileNode> {
        @Override
        public ProfileNode deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            return new ProfileReader(p).read();
        }
    }

    private static class ProfileWriter extends Traverser<ProfileNode> {

        private final JsonGenerator jg;

        private ProfileWriter(ProfileNode rootNode, JsonGenerator jg) throws IOException {
            super(rootNode);
            this.jg = jg;
        }

        private void write() throws IOException {
            traverse();
        }

        @Override
        List<ProfileNode> visit(ProfileNode node) throws IOException {
            jg.writeStartObject();
            jg.writeStringField("stackTraceElement", node.getStackTraceElementStr());
            String leafThreadState = node.getLeafThreadState();
            if (leafThreadState != null) {
                jg.writeStringField("leafThreadState", leafThreadState);
            }
            jg.writeNumberField("sampleCount", node.getSampleCount());
            List<String> timerNames = node.getTimerNames();
            if (!timerNames.isEmpty()) {
                jg.writeArrayFieldStart("timerNames");
                for (String timerName : timerNames) {
                    jg.writeString(timerName);
                }
                jg.writeEndArray();
            }
            boolean ellipsed = node.isEllipsed();
            if (ellipsed) {
                jg.writeBooleanField("ellipsed", ellipsed);
            }
            List<ProfileNode> childNodes = ImmutableList.copyOf(node.getChildNodes());
            if (!childNodes.isEmpty()) {
                jg.writeArrayFieldStart("childNodes");
            }
            return childNodes;
        }

        @Override
        void revisitAfterChildren(ProfileNode node) throws IOException {
            if (!node.isChildNodesEmpty()) {
                jg.writeEndArray();
            }
            jg.writeEndObject();
        }
    }

    private static class ProfileReader {

        private static final SerializableString stackTraceElementName =
                new SerializedString("stackTraceElement");

        private final JsonParser parser;
        private final Deque<ProfileNode> stack = new ArrayDeque<ProfileNode>();

        private ProfileReader(JsonParser parser) {
            this.parser = parser;
        }

        public ProfileNode read() throws IOException {
            ProfileNode rootNode = null;
            while (true) {
                JsonToken token = parser.getCurrentToken();
                if (token == JsonToken.END_ARRAY) {
                    checkState(parser.nextToken() == JsonToken.END_OBJECT);
                    stack.pop();
                    if (stack.isEmpty()) {
                        break;
                    }
                    parser.nextToken();
                    continue;
                }
                checkState(token == JsonToken.START_OBJECT);
                ProfileNode node = readNodeFields();
                ProfileNode parentNode = stack.peek();
                if (parentNode == null) {
                    rootNode = node;
                } else {
                    parentNode.addChildNode(node);
                }
                token = parser.getCurrentToken();
                if (token == JsonToken.FIELD_NAME && parser.getText().equals("childNodes")) {
                    checkState(parser.nextToken() == JsonToken.START_ARRAY);
                    parser.nextToken();
                    stack.push(node);
                    continue;
                }
                checkState(token == JsonToken.END_OBJECT);
                if (stack.isEmpty()) {
                    break;
                }
                parser.nextToken();
            }
            return checkNotNull(rootNode);
        }

        private ProfileNode readNodeFields() throws IOException {
            checkState(parser.nextFieldName(stackTraceElementName));
            String stackTraceElement = parser.nextTextValue();
            String leafThreadState = null;
            JsonToken token = parser.nextToken();
            if (token == JsonToken.FIELD_NAME && parser.getText().equals("leafThreadState")) {
                leafThreadState = parser.nextTextValue();
                token = parser.nextToken();
            }
            ProfileNode node = new ProfileNode(stackTraceElement, leafThreadState);
            if (token == JsonToken.FIELD_NAME && parser.getText().equals("sampleCount")) {
                node.sampleCount = parser.nextIntValue(0);
                token = parser.nextToken();
            }
            if (token == JsonToken.FIELD_NAME && parser.getText().equals("timerNames")) {
                checkState(parser.nextToken() == JsonToken.START_ARRAY);
                List<String> timerNames = Lists.newArrayList();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    timerNames.add(parser.getText());
                }
                node.setTimerNames(ImmutableList.copyOf(timerNames));
                token = parser.nextToken();
            }
            return node;
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    abstract static class MatchedNodePairBase {
        abstract ProfileNode leftNode();
        abstract ProfileNode rightNode();
    }
}
