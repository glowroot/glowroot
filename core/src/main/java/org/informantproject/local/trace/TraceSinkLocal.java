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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.api.JsonCharSequence;
import org.informantproject.api.LargeStringBuilder;
import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.configuration.ImmutableCoreConfiguration;
import org.informantproject.core.stack.MergedStackTree;
import org.informantproject.core.stack.MergedStackTreeNode;
import org.informantproject.core.trace.Span;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceSink;
import org.informantproject.core.util.DaemonExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Implementation of TraceSink for local storage in embedded H2 database. Some day there may be
 * another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceSinkLocal implements TraceSink {

    private static final Logger logger = LoggerFactory.getLogger(TraceSinkLocal.class);

    private final ExecutorService executorService = DaemonExecutors
            .newSingleThreadExecutor("Informant-StackCollector");

    private final ConfigurationService configurationService;
    private final TraceDao traceDao;
    private final StackTraceDao stackTraceDao;

    private final AtomicInteger queueLength = new AtomicInteger(0);

    @Inject
    public TraceSinkLocal(ConfigurationService configurationService, TraceDao traceDao,
            StackTraceDao stackTraceDao) {

        this.configurationService = configurationService;
        this.traceDao = traceDao;
        this.stackTraceDao = stackTraceDao;
    }

    public void onCompletedTrace(final Trace trace) {
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

            queueLength.incrementAndGet();
            executorService.execute(new Runnable() {
                public void run() {
                    traceDao.storeTrace(buildStoredTrace(trace));
                    queueLength.decrementAndGet();
                }
            });
        }
    }

    public void onStuckTrace(Trace trace) {
        traceDao.storeTrace(buildStoredTrace(trace));
    }

    public void shutdown() {
        logger.debug("shutdown()");
        executorService.shutdownNow();
    }

    public int getQueueLength() {
        return queueLength.get();
    }

    public StoredTrace buildStoredTrace(Trace trace) {
        StoredTrace storedTrace = new StoredTrace();
        storedTrace.setId(trace.getId());
        storedTrace.setStartAt(trace.getStartDate().getTime());
        storedTrace.setStuck(trace.isStuck() && !trace.isCompleted());
        storedTrace.setDuration(trace.getDuration());
        storedTrace.setCompleted(trace.isCompleted());
        Gson gson = new Gson();
        storedTrace.setThreadNames(gson.toJson(trace.getThreadNames()));
        storedTrace.setUsername(trace.getUsername());
        try {
            storedTrace.setRootSpan(buildSpan(trace.getRootSpan().getSpans().iterator().next(),
                    gson));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        try {
            storedTrace.setSpans(buildSpans(trace.getRootSpan().getSpans(), gson));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        try {
            storedTrace.setMergedStackTree(buildMergedStackTree(trace.getMergedStackTree()));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return storedTrace;
    }

    private String buildSpan(Span span, Gson gson) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        writeSpan(span, jw, sb, gson);
        jw.close();
        return sb.toString();
    }

    private CharSequence buildSpans(Iterable<Span> spans, Gson gson) throws IOException {
        LargeStringBuilder sb = new LargeStringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginArray();
        for (Span span : spans) {
            writeSpan(span, jw, sb, gson);
        }
        jw.endArray();
        jw.close();
        return sb.build();
    }

    private void writeSpan(Span span, JsonWriter jw, Appendable sb, Gson gson) throws IOException {
        jw.beginObject();
        jw.name("offset");
        jw.value(span.getOffset());
        jw.name("duration");
        jw.value(span.getDuration());
        jw.name("index");
        jw.value(span.getIndex());
        jw.name("parentIndex");
        jw.value(span.getParentIndex());
        jw.name("level");
        jw.value(span.getLevel());
        // inject raw json into stream
        sb.append(",\"description\":\"");
        sb.append(JsonCharSequence.toJson(span.getDescription()));
        sb.append("\"");
        sb.append(",\"contextMap\":");
        sb.append(gson.toJson(span.getContextMap(),
                new TypeToken<Map<String, Object>>() {}.getType()));
        if (span.getStackTraceElements() != null) {
            String stackTraceHash = stackTraceDao.storeStackTrace(span.getStackTraceElements());
            jw.name("stackTraceHash");
            jw.value(stackTraceHash);
        }
        jw.endObject();
    }

    static CharSequence buildMergedStackTree(MergedStackTree mergedStackTree)
            throws IOException {

        MergedStackTreeNode rootNode = mergedStackTree.getRootNode();
        if (rootNode == null) {
            return null;
        }
        LargeStringBuilder sb = new LargeStringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        LinkedList<Object> toVisit = new LinkedList<Object>();
        toVisit.add(rootNode);
        // walk tree depth first
        while (!toVisit.isEmpty()) {
            Object curr = toVisit.removeLast();
            if (curr instanceof MergedStackTreeNode) {
                MergedStackTreeNode currNode = (MergedStackTreeNode) curr;
                jw.beginObject();
                if (currNode.isSyntheticRoot()) {
                    jw.name("stackTraceElement").value("<multiple root nodes>");
                } else {
                    jw.name("stackTraceElement").value(currNode.getStackTraceElement().toString());
                }
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
        jw.close();
        return sb.build();
    }

    private static enum JsonWriterOp {
        END_OBJECT, END_ARRAY
    }
}
