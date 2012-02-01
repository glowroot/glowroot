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
package org.informantproject.local.trace;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.informantproject.configuration.ConfigurationService;
import org.informantproject.configuration.ImmutableCoreConfiguration;
import org.informantproject.trace.Span;
import org.informantproject.trace.Trace;
import org.informantproject.trace.TraceSink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;

/**
 * Implementation of TraceSink for local storage in embedded H2 database. Some day there may be
 * another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceSinkLocal implements TraceSink {

    private final ConfigurationService configurationService;
    private final TraceDao traceDao;

    @Inject
    public TraceSinkLocal(ConfigurationService configurationService, TraceDao traceDao) {
        this.configurationService = configurationService;
        this.traceDao = traceDao;
    }

    public void onCompletedTrace(Trace trace) {
        ImmutableCoreConfiguration configuration = configurationService.getCoreConfiguration();
        int thresholdMillis = configuration.getThresholdMillis();
        boolean thresholdDisabled =
                (thresholdMillis == ImmutableCoreConfiguration.THRESHOLD_DISABLED);
        long durationInNanoseconds = trace.getRootSpan().getDuration();
        // if the completed trace exceeded the given threshold then it is sent to the sink. the
        // completed trace is also checked in case it was previously sent to the sink and marked as
        // stuck, and the threshold was disabled or increased in the meantime, in which case the
        // full completed trace needs to be (re-)sent to the sink
        if ((!thresholdDisabled && durationInNanoseconds >= TimeUnit.MILLISECONDS
                .toNanos(thresholdMillis)) || trace.isStuck()) {

            traceDao.storeTrace(buildStoredTrace(trace));
        }
    }

    public void onStuckTrace(Trace trace) {
        traceDao.storeTrace(buildStoredTrace(trace));
    }

    // package protected for unit tests
    static StoredTrace buildStoredTrace(Trace trace) {
        StoredTrace storedTrace = new StoredTrace();
        storedTrace.setId(trace.getId());
        storedTrace.setStartAt(trace.getStartDate().getTime());
        storedTrace.setStuck(trace.isStuck());
        storedTrace.setDuration(trace.getDuration());
        storedTrace.setCompleted(trace.isCompleted());
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(Span.class, new SpanGsonSerializer())
                .registerTypeAdapter(StackTraceElement.class, new StackTraceElementGsonSerializer())
                .create();
        storedTrace.setThreadNames(gson.toJson(trace.getThreadNames()));
        storedTrace.setUsername(trace.getUsername());
        storedTrace.setSpans(gson.toJson(trace.getRootSpan().getSpans()));
        storedTrace.setMergedStackTreeRootNodes(gson.toJson(trace.getMergedStackTree()
                .getRootNodes()));
        return storedTrace;
    }

    private static class SpanGsonSerializer implements JsonSerializer<Span> {
        public JsonElement serialize(Span span, Type unused, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("offset", span.getOffset());
            jsonObject.addProperty("duration", span.getDuration());
            jsonObject.addProperty("index", span.getIndex());
            jsonObject.addProperty("parentIndex", span.getParentIndex());
            jsonObject.addProperty("level", span.getLevel());
            jsonObject.addProperty("description", span.getDescription());
            jsonObject.add("contextMap", context.serialize(span.getContextMap(),
                    new TypeToken<Map<String, Object>>() {}.getType()));
            return jsonObject;
        }
    }

    private static class StackTraceElementGsonSerializer implements
            JsonSerializer<StackTraceElement> {
        public JsonElement serialize(StackTraceElement stackTraceElement, Type unused,
                JsonSerializationContext context) {
            return new JsonPrimitive(stackTraceElement.toString());
        }
    }
}
