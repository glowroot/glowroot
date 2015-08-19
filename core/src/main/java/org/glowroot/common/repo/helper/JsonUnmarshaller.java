/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.common.repo.helper;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.immutables.value.Value;

import org.glowroot.agent.model.ImmutableThrowableInfo;
import org.glowroot.collector.spi.GarbageCollectionActivity;
import org.glowroot.collector.spi.ThrowableInfo;
import org.glowroot.collector.spi.TraceTimerNode;
import org.glowroot.common.repo.MutableProfileNode;
import org.glowroot.common.repo.MutableQuery;
import org.glowroot.common.repo.MutableTimerNode;
import org.glowroot.common.util.ObjectMappers;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class JsonUnmarshaller {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private JsonUnmarshaller() {}

    public static MutableTimerNode unmarshalAggregateTimers(String timers) throws IOException {
        return mapper.readValue(timers, MutableTimerNode.class);
    }

    public static MutableProfileNode unmarshalProfile(Reader reader) throws IOException {
        JsonParser jp = mapper.getFactory().createParser(reader);
        jp.nextToken();
        MutableProfileNode profileNode = new ProfileReader(jp).read();
        // assert that reader has been drained
        checkState(jp.nextToken() == null);
        return profileNode;
    }

    public static Map<String, List<MutableQuery>> unmarshalQueries(Reader reader)
            throws IOException {
        return mapper.readValue(reader, new TypeReference<Map<String, List<MutableQuery>>>() {});
    }

    public static Map<String, Collection<String>> unmarshalCustomAttributes(
            @Nullable String customAttributes) throws IOException {
        if (customAttributes == null) {
            return ImmutableMap.of();
        }
        return mapper.readerFor(new TypeReference<Map<String, Collection<String>>>() {})
                .readValue(customAttributes);
    }

    public static Map<String, ? extends /*@Nullable*/Object> unmarshalDetailMap(
            @Nullable String detail) throws IOException {
        if (detail == null) {
            return ImmutableMap.of();
        }
        return mapper.readerFor(new TypeReference<Map<String, Object>>() {}).readValue(detail);
    }

    public static @Nullable ThrowableInfo unmarshalThrowable(@Nullable String throwable)
            throws IOException {
        if (throwable == null) {
            return null;
        }
        return mapper.readerFor(ImmutableThrowableInfo.class).readValue(throwable);
    }

    public static TraceTimerNode unmarshalTraceTimers(String timers) throws IOException {
        return mapper.readerFor(ImmutableXTraceTimerNode.class).readValue(timers);
    }

    public static Map<String, GarbageCollectionActivity> unmarshalGcActivity(
            @Nullable String gcActivity) throws IOException {
        if (gcActivity == null) {
            return ImmutableMap.of();
        }
        return mapper
                .readerFor(new TypeReference<Map<String, ImmutableXGarbageCollectionActivity>>() {})
                .readValue(gcActivity);
    }

    // custom deserializer to avoid StackOverflowError caused by default recursive algorithm
    private static class ProfileReader {

        private static final SerializableString stackTraceElementName =
                new SerializedString("stackTraceElement");

        private final JsonParser parser;
        private final Deque<MutableProfileNode> stack = new ArrayDeque<MutableProfileNode>();

        private ProfileReader(JsonParser parser) {
            this.parser = parser;
        }

        public MutableProfileNode read() throws IOException {
            MutableProfileNode rootNode = null;
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
                MutableProfileNode node = readNodeFields();
                MutableProfileNode parentNode = stack.peek();
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

        private MutableProfileNode readNodeFields() throws IOException {
            checkState(parser.nextFieldName(stackTraceElementName));
            JsonToken token = parser.nextToken();
            StackTraceElement stackTraceElement;
            if (token == JsonToken.VALUE_NULL) {
                stackTraceElement = null;
            } else {
                checkState(token == JsonToken.START_ARRAY);
                String className = parser.nextTextValue();
                String methodName = parser.nextTextValue();
                String fileName = parser.nextTextValue();
                int lineNumber = parser.nextIntValue(0);
                stackTraceElement =
                        new StackTraceElement(className, methodName, fileName, lineNumber);
                checkState(parser.nextToken() == JsonToken.END_ARRAY);
            }
            String leafThreadState = null;
            token = parser.nextToken();
            if (token == JsonToken.FIELD_NAME && parser.getText().equals("leafThreadState")) {
                leafThreadState = parser.nextTextValue();
                token = parser.nextToken();
            }
            MutableProfileNode node = new MutableProfileNode(stackTraceElement, leafThreadState);
            if (token == JsonToken.FIELD_NAME && parser.getText().equals("sampleCount")) {
                node.setSampleCount(parser.nextLongValue(0));
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

    // TODO use @Value.Include for this
    @Value.Immutable
    interface XGarbageCollectionActivity extends GarbageCollectionActivity {}

    @Value.Immutable
    abstract static class XTraceTimerNode implements TraceTimerNode {

        @Override
        @Value.Default
        public Collection<ImmutableXTraceTimerNode> childNodes() {
            return ImmutableList.of();
        }
    }
}
