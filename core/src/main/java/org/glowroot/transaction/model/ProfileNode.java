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
import java.util.List;

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
import org.immutables.value.Value;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

@JsonSerialize(using = ProfileNode.Serializer.class)
@JsonDeserialize(using = ProfileNode.Deserializer.class)
public class ProfileNode {

    // null for synthetic root only
    private final @Nullable String stackTraceElement;
    private final @Nullable String leafThreadState;
    private int sampleCount;
    // using List over Set in order to preserve ordering
    // may contain duplicates (common from weaving groups of overloaded methods), these are filtered
    // out later when profile is written to json
    private List<String> timerNames = Lists.newArrayList();
    // nodes mostly have a single child node, and rarely have more than two child nodes
    private final List<ProfileNode> childNodes = Lists.newArrayListWithCapacity(2);
    // this is only used when sending profiles to the UI (it is not used when storing)
    private boolean ellipsed;

    public static ProfileNode createSyntheticRoot() {
        return new ProfileNode(null, null);
    }

    public static ProfileNode create(String stackTraceElement, @Nullable String leafThreadState) {
        return new ProfileNode(stackTraceElement, leafThreadState);
    }

    private ProfileNode(@Nullable String stackTraceElement,
            @Nullable String leafThreadState) {
        this.stackTraceElement = stackTraceElement;
        this.leafThreadState = leafThreadState;
    }

    public void addChildNode(ProfileNode node) {
        childNodes.add(node);
    }

    // may contain duplicates
    public void setTimerNames(List<String> timerNames) {
        this.timerNames = ImmutableList.copyOf(timerNames);
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
    public @Nullable String getStackTraceElement() {
        return stackTraceElement;
    }

    public @Nullable String getLeafThreadState() {
        return leafThreadState;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    // may contain duplicates
    public List<String> getTimerNames() {
        return timerNames;
    }

    public List<ProfileNode> getChildNodes() {
        return childNodes;
    }

    public boolean isEllipsed() {
        return ellipsed;
    }

    public void mergeMatchedNode(ProfileNode anotherSyntheticRootNode) {
        // can only be called on synthetic root node
        checkState(stackTraceElement == null);
        merge(this, anotherSyntheticRootNode);
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
                ProfileNode matchingLeftChildNode = findMatch(leftNode.childNodes, rightChildNode);
                if (matchingLeftChildNode == null) {
                    leftNode.addChildNode(rightChildNode);
                } else {
                    stack.push(MatchedNodePair.of(matchingLeftChildNode, rightChildNode));
                }
            }
        }
    }

    private static @Nullable ProfileNode findMatch(List<ProfileNode> leftChildNodes,
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
        List<String> timerNames = rightNode.getTimerNames();
        if (timerNames.size() > leftNode.timerNames.size()) {
            leftNode.timerNames = timerNames;
        }
    }

    private static boolean matches(ProfileNode leftNode, ProfileNode rightNode) {
        return Objects.equal(leftNode.getStackTraceElement(), rightNode.getStackTraceElement())
                && Objects.equal(leftNode.getLeafThreadState(), rightNode.getLeafThreadState());
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
            jg.writeStringField("stackTraceElement", node.getStackTraceElement());
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
            List<ProfileNode> childNodes = node.getChildNodes();
            if (!childNodes.isEmpty()) {
                jg.writeArrayFieldStart("childNodes");
            }
            return childNodes;
        }

        @Override
        void revisitAfterChildren(ProfileNode node) throws IOException {
            List<ProfileNode> childNodes = node.getChildNodes();
            if (!childNodes.isEmpty()) {
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
            checkNotNull(rootNode);
            return rootNode;
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
                node.setTimerNames(timerNames);
                token = parser.nextToken();
            }
            return node;
        }
    }

    @Value.Immutable
    abstract static class MatchedNodePairBase {
        @Value.Parameter
        abstract ProfileNode leftNode();
        @Value.Parameter
        abstract ProfileNode rightNode();
    }
}
