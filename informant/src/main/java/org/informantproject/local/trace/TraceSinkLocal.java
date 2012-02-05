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

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.informantproject.configuration.ConfigurationService;
import org.informantproject.configuration.ImmutableCoreConfiguration;
import org.informantproject.stack.MergedStackTree;
import org.informantproject.stack.MergedStackTreeNode;
import org.informantproject.trace.Span;
import org.informantproject.trace.Trace;
import org.informantproject.trace.TraceSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;

/**
 * Implementation of TraceSink for local storage in embedded H2 database. Some day there may be
 * another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceSinkLocal implements TraceSink {

    private static final Logger logger = LoggerFactory.getLogger(TraceSinkLocal.class);

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
                .create();
        storedTrace.setThreadNames(gson.toJson(trace.getThreadNames()));
        storedTrace.setUsername(trace.getUsername());
        storedTrace.setSpans(gson.toJson(trace.getRootSpan().getSpans()));
        try {
            storedTrace.setMergedStackTreeRootNodes(buildMergedStackTreeRootNodes(trace
                    .getMergedStackTree()));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return storedTrace;
    }

    private static String buildMergedStackTreeRootNodes(MergedStackTree mergedStackTree)
            throws IOException {

        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.beginArray();
        for (MergedStackTreeNode rootNode : mergedStackTree.getRootNodes()) {
            writeMergedStackTreeRootNode(rootNode, jw);
        }
        jw.endArray();
        jw.close();
        return sw.toString();
    }

    private static void writeMergedStackTreeRootNode(MergedStackTreeNode rootNode, JsonWriter jw)
            throws IOException {

        // walk tree depth first
        LinkedList<Object> toVisit = new LinkedList<Object>();
        toVisit.add(rootNode);
        while (!toVisit.isEmpty()) {
            Object curr = toVisit.removeLast();
            if (curr instanceof MergedStackTreeNode) {
                MergedStackTreeNode currNode = (MergedStackTreeNode) curr;
                jw.beginObject();
                jw.name("stackTraceElement").value(currNode.getStackTraceElement().toString());
                jw.name("sampleCount").value(currNode.getSampleCount());
                if (currNode.isLeaf()) {
                    jw.name("leafThreadState").value(currNode.getLeafThreadState().name());
                }
                List<MergedStackTreeNode> childNodes = Lists.newArrayList(currNode.getChildNodes());
                if (childNodes.isEmpty()) {
                    jw.endObject();
                } else {
                    toVisit.add(JsonWriterOp.END_OBJECT);
                    jw.name("childNodes").beginArray();
                    toVisit.add(JsonWriterOp.END_ARRAY);
                    toVisit.addAll(Lists.reverse(childNodes));
                }
            } else if (curr == JsonWriterOp.END_ARRAY) {
                jw.endArray();
            } else if (curr == JsonWriterOp.END_OBJECT) {
                jw.endObject();
            }
        }
    }

    private static enum JsonWriterOp {
        END_OBJECT, END_ARRAY
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
