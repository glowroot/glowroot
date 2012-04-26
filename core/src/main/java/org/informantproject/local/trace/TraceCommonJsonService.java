/**
 * Copyright 2012 the original author or authors.
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.informantproject.api.LargeStringBuilder;
import org.informantproject.api.Optional;
import org.informantproject.api.SpanContextMap;
import org.informantproject.core.stack.MergedStackTreeNode;
import org.informantproject.core.trace.SpanImpl;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceMetricImpl;
import org.informantproject.core.trace.TraceMetricImpl.Snapshot;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.core.util.ByteStream;
import org.informantproject.core.util.CharSequences;
import org.informantproject.core.util.OptionalJsonSerializer;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Longs;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceCommonJsonService {

    private final TraceDao traceDao;
    private final TraceRegistry traceRegistry;
    private final StackTraceDao stackTraceDao;
    private final Ticker ticker;

    @Inject
    public TraceCommonJsonService(TraceDao traceDao, TraceRegistry traceRegistry,
            StackTraceDao stackTraceDao, Ticker ticker) {

        this.traceDao = traceDao;
        this.traceRegistry = traceRegistry;
        this.stackTraceDao = stackTraceDao;
        this.ticker = ticker;
    }

    public Optional<ByteStream> getStoredOrActiveTraceJson(String id, boolean includeDetail)
            throws IOException {

        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Trace active : traceRegistry.getTraces()) {
            if (active.getId().equals(id)) {
                return Optional.of(getActiveTraceJson(active, includeDetail));
            }
        }
        StoredTrace storedTrace = traceDao.readStoredTrace(id);
        if (storedTrace == null) {
            return Optional.absent();
        } else {
            return Optional.of(getStoredTraceJson(storedTrace, includeDetail));
        }
    }

    private ByteStream getStoredTraceJson(StoredTrace storedTrace, boolean includeDetail)
            throws UnsupportedEncodingException {

        List<ByteStream> byteStreams = Lists.newArrayList();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"");
        sb.append(storedTrace.getId());
        sb.append("\",\"start\":");
        sb.append(storedTrace.getStartAt());
        sb.append(",\"stuck\":");
        sb.append(storedTrace.isStuck());
        sb.append(",\"duration\":");
        sb.append(storedTrace.getDuration());
        sb.append(",\"completed\":");
        sb.append(storedTrace.isCompleted());
        sb.append(",\"description\":\"");
        sb.append(storedTrace.getDescription());
        sb.append("\"");
        if (storedTrace.getUsername() != null) {
            sb.append(",\"username\":\"");
            sb.append(storedTrace.getUsername());
            sb.append("\"");
        }
        // inject raw json into stream
        if (storedTrace.getAttributes() != null) {
            sb.append(",\"attributes\":");
            sb.append(storedTrace.getAttributes());
        }
        if (storedTrace.getMetrics() != null) {
            sb.append(",\"metrics\":");
            sb.append(storedTrace.getMetrics());
        }
        if (includeDetail && storedTrace.getSpans() != null) {
            // spans could be null if spans text has been rolled out
            sb.append(",\"spans\":");
            // flush current StringBuilder as its own chunk and reset StringBuffer
            byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
            sb.setLength(0);
            byteStreams.add(storedTrace.getSpans());
        }
        if (includeDetail && storedTrace.getMergedStackTree() != null) {
            sb.append(",\"mergedStackTree\":");
            // flush current StringBuilder as its own chunk and reset StringBuffer
            byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
            sb.setLength(0);
            byteStreams.add(storedTrace.getMergedStackTree());
        }
        sb.append("}");
        // flush current StringBuilder as its own chunk
        byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
        return ByteStream.of(byteStreams);
    }

    private ByteStream getActiveTraceJson(Trace activeTrace, boolean includeDetail)
            throws IOException {

        long captureTick = ticker.read();
        // there is a chance for slight inconsistency since this is reading active traces which are
        // still being modified and/or may even reach completion while they are being written
        List<ByteStream> byteStreams = Lists.newArrayList();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"active\":true");
        sb.append(",\"id\":\"");
        sb.append(activeTrace.getId());
        sb.append("\",\"start\":");
        sb.append(activeTrace.getStartDate().getTime());
        sb.append(",\"stuck\":");
        sb.append(activeTrace.isStuck());
        sb.append(",\"duration\":");
        sb.append(activeTrace.getDuration());
        sb.append(",\"completed\":");
        sb.append(activeTrace.isCompleted());
        SpanImpl rootSpan = activeTrace.getRootSpan().getSpans().iterator().next();
        sb.append(",\"description\":\"");
        sb.append(rootSpan.getDescription().toString());
        sb.append("\"");
        Optional<String> username = activeTrace.getUsername();
        if (username.isPresent()) {
            sb.append(",\"username\":\"");
            sb.append(username.get());
            sb.append("\"");
        }
        // OptionalJsonSerializer is needed for serializing trace attributes and span context maps
        Gson gson = new GsonBuilder().registerTypeHierarchyAdapter(Optional.class,
                new OptionalJsonSerializer()).create();
        String attributes = getAttributesJson(activeTrace, gson);
        if (attributes != null) {
            sb.append(",\"attributes\":");
            sb.append(attributes);
        }
        String metrics = getMetricsJson(activeTrace, gson);
        if (metrics != null) {
            sb.append(",\"metrics\":");
            sb.append(metrics);
        }
        if (includeDetail) {
            ByteStream spans = getSpansByteStream(activeTrace, captureTick, gson);
            sb.append(",\"spans\":");
            // flush current StringBuilder as its own chunk and reset StringBuffer
            byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
            sb.setLength(0);
            byteStreams.add(spans);
            ByteStream mergedStackTree = getMergedStackTree(activeTrace);
            if (mergedStackTree != null) {
                sb.append(",\"mergedStackTree\":");
                // flush current StringBuilder as its own chunk and reset StringBuffer
                byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
                sb.setLength(0);
                byteStreams.add(mergedStackTree);
            }
        }
        sb.append("}");
        // flush current StringBuilder as its own chunk
        byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
        return ByteStream.of(byteStreams);
    }

    public ByteStream getSpansByteStream(Trace trace, long captureTick, Gson gson)
            throws IOException {

        return new SpansByteStream(trace.getRootSpan().getSpans().iterator(), captureTick, gson);
    }

    public static String getMetricsJson(Trace trace, Gson gson) {
        Collection<Snapshot> items = Collections2.transform(trace.getTraceMetrics(),
                new Function<TraceMetricImpl, Snapshot>() {
                    public Snapshot apply(TraceMetricImpl item) {
                        return item.copyOf();
                    }
                });
        if (items.isEmpty()) {
            return null;
        } else {
            Ordering<Snapshot> byTotalOrdering = new Ordering<Snapshot>() {
                @Override
                public int compare(Snapshot left, Snapshot right) {
                    return Longs.compare(left.getTotal(), right.getTotal());
                }
            };
            return gson.toJson(byTotalOrdering.reverse().sortedCopy(items));
        }
    }

    public static String getAttributesJson(Trace trace, Gson gson) {
        SpanImpl rootSpan = trace.getRootSpan().getSpans().iterator().next();
        // Span.getContextMap() may be creating the context map on the fly, so don't call it twice
        SpanContextMap contextMap = rootSpan.getContextMap();
        if (contextMap == null) {
            return null;
        } else {
            return gson.toJson(contextMap, new TypeToken<Map<String, Object>>() {}.getType());
        }
    }

    public static ByteStream getMergedStackTree(Trace trace) {
        if (trace.getMergedStackTree().getRootNode() == null) {
            return null;
        }
        MergedStackTreeNode rootNode = trace.getMergedStackTree().getRootNode();
        LinkedList<Object> toVisit = new LinkedList<Object>();
        toVisit.add(rootNode);
        return new MergedStackTreeByteStream(toVisit);
    }

    private static String getStackTraceJson(StackTraceElement[] stackTraceElements)
            throws IOException {

        LargeStringBuilder sb = new LargeStringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginArray();
        for (StackTraceElement stackTraceElement : stackTraceElements) {
            jw.value(stackTraceElement.toString());
        }
        jw.endArray();
        jw.close();
        return sb.toString();
    }

    private class SpansByteStream extends ByteStream {

        private static final int TARGET_CHUNK_SIZE = 8192;

        private final Iterator<SpanImpl> spans;
        private final Map<String, String> stackTraces = Maps.newHashMap();
        private final long captureTick;
        private final Gson gson;
        private final ByteArrayOutputStream baos;
        private final Writer raw;
        private final JsonWriter jw;

        private SpansByteStream(Iterator<SpanImpl> spans, long captureTick, Gson gson)
                throws IOException {

            this.spans = spans;
            this.captureTick = captureTick;
            this.gson = gson;
            baos = new ByteArrayOutputStream(2 * TARGET_CHUNK_SIZE);
            raw = new OutputStreamWriter(baos, Charsets.UTF_8);
            jw = new JsonWriter(raw);
            jw.beginArray();
        }

        @Override
        public boolean hasNext() {
            return spans.hasNext();
        }

        @Override
        public byte[] next() throws IOException {
            while (baos.size() < TARGET_CHUNK_SIZE && hasNext()) {
                writeSpan(spans.next());
                jw.flush();
            }
            if (!hasNext()) {
                jw.endArray();
                jw.close();
                if (!stackTraces.isEmpty()) {
                    stackTraceDao.storeStackTraces(stackTraces);
                }
            }
            byte[] chunk = baos.toByteArray();
            baos.reset();
            return chunk;
        }

        // timings for traces that are still active are normalized to the capture tick in order to
        // *attempt* to present a picture of the trace at that exact tick
        // (without using synchronization to block updates to the trace while it is being read)
        private void writeSpan(SpanImpl span) throws IOException {
            if (span.getStartTick() > captureTick) {
                // this span started after the capture tick
                return;
            }
            jw.beginObject();
            jw.name("offset");
            jw.value(span.getOffset());
            jw.name("duration");
            long endTick = span.getEndTick();
            if (endTick != 0 && endTick <= captureTick) {
                jw.value(span.getEndTick() - span.getStartTick());
            } else {
                jw.value(captureTick - span.getStartTick());
                jw.name("active");
                jw.value(true);
            }
            jw.name("index");
            jw.value(span.getIndex());
            jw.name("parentIndex");
            jw.value(span.getParentIndex());
            jw.name("level");
            jw.value(span.getLevel());
            // inject raw json into stream
            raw.append(",\"description\":\"");
            raw.append(CharSequences.toJson(span.getDescription()));
            raw.append("\"");
            // Span.getContextMap() may be creating the context map on the fly, so don't call it
            // twice
            SpanContextMap contextMap = span.getContextMap();
            if (contextMap != null) {
                raw.append(",\"contextMap\":");
                raw.append(gson.toJson(contextMap));
            }
            if (span.getStackTraceElements() != null) {
                String stackTraceJson = getStackTraceJson(span.getStackTraceElements());
                String stackTraceHash = Hashing.sha1().hashString(stackTraceJson, Charsets.UTF_8)
                        .toString();
                stackTraces.put(stackTraceHash, stackTraceJson);
                jw.name("stackTraceHash");
                jw.value(stackTraceHash);
            }
            jw.endObject();
        }
    }

    private static class MergedStackTreeByteStream extends ByteStream {

        private static final int TARGET_CHUNK_SIZE = 8192;

        private static final Pattern metricMarkerMethodPattern = Pattern.compile(
                "^.*\\$informant\\$metric\\$(.*)\\$[0-9]+$");

        private final LinkedList<Object> toVisit;
        private final ByteArrayOutputStream baos;
        private final JsonWriter jw;
        private final List<String> metricNameStack = Lists.newArrayList();

        private MergedStackTreeByteStream(LinkedList<Object> toVisit) {
            this.toVisit = toVisit;
            baos = new ByteArrayOutputStream(2 * TARGET_CHUNK_SIZE);
            jw = new JsonWriter(new OutputStreamWriter(baos, Charsets.UTF_8));
        }

        @Override
        public boolean hasNext() {
            return !toVisit.isEmpty();
        }

        @Override
        public byte[] next() throws IOException {
            while (baos.size() < TARGET_CHUNK_SIZE && hasNext()) {
                writeNext();
                jw.flush();
            }
            if (!hasNext()) {
                jw.close();
            }
            byte[] chunk = baos.toByteArray();
            baos.reset();
            return chunk;
        }

        private void writeNext() throws IOException {
            Object curr = toVisit.removeLast();
            if (curr instanceof MergedStackTreeNode) {
                MergedStackTreeNode currNode = (MergedStackTreeNode) curr;
                jw.beginObject();
                toVisit.add(JsonWriterOp.END_OBJECT);
                if (currNode.isSyntheticRoot()) {
                    jw.name("stackTraceElement").value("<multiple root nodes>");
                } else {
                    jw.name("stackTraceElement").value(
                            currNode.getStackTraceElement().toString());
                }
                String newMetricName = getMetricName(currNode.getStackTraceElement());
                if (newMetricName != null) {
                    // filter out successive duplicates which are common from weaving groups of
                    // overloaded methods
                    if (!newMetricName.equals(top(metricNameStack))) {
                        metricNameStack.add(newMetricName);
                        toVisit.add(JsonWriterOp.POP_METRIC_NAME);
                    }
                }
                jw.name("sampleCount").value(currNode.getSampleCount());
                if (currNode.isLeaf()) {
                    jw.name("leafThreadState").value(currNode.getLeafThreadState().name());
                    jw.name("metricNames");
                    jw.beginArray();
                    for (String metricName : metricNameStack) {
                        jw.value(metricName);
                    }
                    jw.endArray();
                }
                List<MergedStackTreeNode> childNodes = Lists.newArrayList(currNode
                        .getChildNodes());
                if (!childNodes.isEmpty()) {
                    jw.name("childNodes").beginArray();
                    toVisit.add(JsonWriterOp.END_ARRAY);
                    toVisit.addAll(Lists.reverse(childNodes));
                }
            } else if (curr == JsonWriterOp.END_ARRAY) {
                jw.endArray();
            } else if (curr == JsonWriterOp.END_OBJECT) {
                jw.endObject();
            } else if (curr == JsonWriterOp.POP_METRIC_NAME) {
                metricNameStack.remove(metricNameStack.size() - 1);
            }
        }

        private static String top(List<String> stack) {
            if (stack.isEmpty()) {
                return null;
            } else {
                return stack.get(stack.size() - 1);
            }
        }

        private static String getMetricName(StackTraceElement stackTraceElement) {
            return getMetricNameFromMethodName(stackTraceElement);
        }

        private static String getMetricNameFromMethodName(StackTraceElement stackTraceElement) {
            Matcher matcher = metricMarkerMethodPattern.matcher(stackTraceElement.getMethodName());
            if (matcher.matches()) {
                return matcher.group(1).replace("$", " ");
            } else {
                return null;
            }
        }

        private static enum JsonWriterOp {
            END_OBJECT, END_ARRAY, POP_METRIC_NAME;
        }
    }
}
