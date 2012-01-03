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

import org.informantproject.configuration.ConfigurationService;
import org.informantproject.trace.Span;
import org.informantproject.trace.Trace;
import org.informantproject.trace.TraceSink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
public class TraceSinkLocal extends TraceSink {

    private final TraceDao traceDao;

    @Inject
    public TraceSinkLocal(TraceDao traceDao, ConfigurationService configurationService) {
        super(configurationService);
        this.traceDao = traceDao;
    }

    protected void storeTrace(Trace trace) {
        traceDao.storeTrace(buildStoredTrace(trace));
    }

    private static StoredTrace buildStoredTrace(Trace trace) {
        StoredTrace storedTrace = new StoredTrace();
        storedTrace.setId(trace.getId());
        storedTrace.setStartAt(trace.getStartDate().getTime());
        storedTrace.setStuck(trace.isStuck());
        storedTrace.setDuration(trace.getDuration());
        storedTrace.setCompleted(trace.isCompleted());
        Gson gson = new GsonBuilder().registerTypeAdapter(Span.class, new SpanGsonSerializer())
                .create();
        storedTrace.setThreadNames(gson.toJson(trace.getThreadNames()));
        storedTrace.setUsername(trace.getUsername());
        storedTrace.setSpans(gson.toJson(trace.getRootSpan().getSpans()));
        storedTrace.setMergedStackTree(gson.toJson(trace.getMergedStackTree()));
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
}
