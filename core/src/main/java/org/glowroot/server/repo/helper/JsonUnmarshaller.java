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
package org.glowroot.server.repo.helper;

import java.io.IOException;
import java.io.Reader;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

import org.glowroot.agent.model.ImmutableThrowableInfo;
import org.glowroot.collector.spi.GarbageCollectorActivity;
import org.glowroot.collector.spi.ThrowableInfo;
import org.glowroot.collector.spi.TraceTimerNode;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.MutableTimerNode;
import org.glowroot.common.util.ObjectMappers;

public class JsonUnmarshaller {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private JsonUnmarshaller() {}

    public static MutableTimerNode unmarshalAggregateTimers(String timers) throws IOException {
        return mapper.readValue(timers, MutableTimerNode.class);
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

    public static List<GarbageCollectorActivity> unmarshalGcActivity(@Nullable String gcActivity)
            throws IOException {
        if (gcActivity == null) {
            return ImmutableList.of();
        }
        return mapper
                .readerFor(new TypeReference<List<ImmutableXGarbageCollectionActivity>>() {})
                .readValue(gcActivity);
    }

    // TODO use @Value.Include for this
    @Value.Immutable
    interface XGarbageCollectionActivity extends GarbageCollectorActivity {}

    @Value.Immutable
    abstract static class XTraceTimerNode implements TraceTimerNode {

        @Override
        @Value.Default
        public Collection<ImmutableXTraceTimerNode> childNodes() {
            return ImmutableList.of();
        }
    }
}
