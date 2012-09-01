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
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.informantproject.api.ErrorMessage;
import org.informantproject.api.Message;
import org.informantproject.api.Supplier;
import org.informantproject.core.stack.MergedStackTreeNode;
import org.informantproject.core.trace.Span;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.Trace.TraceAttribute;
import org.informantproject.core.trace.TraceMetric;
import org.informantproject.core.trace.TraceMetric.Snapshot;
import org.informantproject.core.util.ByteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(TraceSnapshotService.class);

    private static final Gson gson = new Gson();

    private final StackTraceDao stackTraceDao;

    @Inject
    TraceSnapshotService(StackTraceDao stackTraceDao) {
        this.stackTraceDao = stackTraceDao;
    }

    public TraceSnapshot from(Trace trace, long captureTick) throws IOException {
        return from(trace, captureTick, true);
    }

    public TraceSnapshot from(Trace trace, long captureTick, boolean includeDetail)
            throws IOException {

        TraceSnapshot.Builder builder = TraceSnapshot.builder();
        builder.id(trace.getId());
        builder.startAt(trace.getStartDate().getTime());
        builder.stuck(trace.isStuck() && !trace.isCompleted());
        // timings for traces that are still active are normalized to the capture tick in order to
        // *attempt* to present a picture of the trace at that exact tick
        // (without using synchronization to block updates to the trace while it is being read)
        long endTick = trace.getEndTick();
        if (endTick != 0 && endTick <= captureTick) {
            builder.duration(trace.getDuration());
            builder.completed(true);
        } else {
            builder.duration(captureTick - trace.getStartTick());
            builder.completed(false);
        }
        Message message = trace.getRootSpan().getMessageSupplier().get();
        builder.description(message.getText());
        ErrorMessage errorMessage = trace.getRootSpan().getErrorMessage();
        if (errorMessage != null) {
            builder.errorText(errorMessage.getText());
            Map<String, ?> detail = errorMessage.getDetail();
            if (detail != null) {
                StringBuilder sb = new StringBuilder();
                JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
                new MessageDetailSerializer(jw).write(detail);
                jw.close();
                builder.errorDetail(sb.toString());
            }
            StackTraceElement[] stackTrace = errorMessage.getStackTrace();
            if (stackTrace != null) {
                builder.errorStackTrace(getStackTraceJson(stackTrace));
            }
        }
        List<TraceAttribute> attributes = trace.getAttributes();
        if (!attributes.isEmpty()) {
            builder.attributes(gson.toJson(attributes));
        }
        builder.username(trace.getUsernameSupplier().get());
        builder.metrics(getMetricsJson(trace));
        if (includeDetail) {
            SpansByteStream spansByteStream = new SpansByteStream(trace.getSpans(), captureTick,
                    stackTraceDao);
            builder.spans(spansByteStream);
            builder.mergedStackTree(TraceSnapshotService.getMergedStackTree(trace));
        }
        return builder.build();
    }

    public static ByteStream toByteStream(TraceSnapshot snapshot)
            throws UnsupportedEncodingException {

        List<ByteStream> byteStreams = Lists.newArrayList();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"");
        sb.append(snapshot.getId());
        sb.append("\",\"start\":");
        sb.append(snapshot.getStartAt());
        sb.append(",\"stuck\":");
        sb.append(snapshot.isStuck());
        sb.append(",\"duration\":");
        sb.append(snapshot.getDuration());
        sb.append(",\"completed\":");
        sb.append(snapshot.isCompleted());
        sb.append(",\"description\":");
        sb.append(escapeJson(snapshot.getDescription()));
        if (snapshot.getAttributes() != null) {
            sb.append(",\"attributes\":");
            sb.append(snapshot.getAttributes());
        }
        if (snapshot.getUsername() != null) {
            sb.append(",\"username\":");
            sb.append(escapeJson(snapshot.getUsername()));
        }
        if (snapshot.getErrorText() != null) {
            sb.append(",\"error\":{\"text\":");
            sb.append(escapeJson(snapshot.getErrorText()));
            if (snapshot.getErrorDetail() != null) {
                sb.append(",\"detail\":");
                sb.append(snapshot.getErrorDetail());
            }
            if (snapshot.getErrorStackTrace() != null) {
                sb.append(",\"stackTrace\":");
                sb.append(snapshot.getErrorStackTrace());
            }
            sb.append("}");
        }
        if (snapshot.getMetrics() != null) {
            sb.append(",\"metrics\":");
            sb.append(snapshot.getMetrics());
        }
        if (snapshot.getSpans() != null) {
            sb.append(",\"spans\":");
            // flush current StringBuilder as its own chunk and reset StringBuffer
            byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
            sb.setLength(0);
            byteStreams.add(snapshot.getSpans());
        }
        if (snapshot.getMergedStackTree() != null) {
            sb.append(",\"mergedStackTree\":");
            // flush current StringBuilder as its own chunk and reset StringBuffer
            byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
            sb.setLength(0);
            byteStreams.add(snapshot.getMergedStackTree());
        }
        sb.append("}");
        // flush current StringBuilder as its own chunk
        byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
        return ByteStream.of(byteStreams);
    }

    private static String escapeJson(String s) {
        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.setLenient(true);
        try {
            jw.value(s);
            jw.close();
            return sw.toString();
        } catch (IOException e) {
            // this can't really happen since StringWriter doesn't throw IOException
            return "error (" + e.getClass().getName() + ") occurred escaping json string";
        }
    }

    @Nullable
    private static String getMetricsJson(Trace trace) {
        List<TraceMetric> traceMetrics = trace.getTraceMetrics();
        if (traceMetrics.isEmpty()) {
            return null;
        }
        List<Snapshot> items = Lists.newArrayList();
        for (TraceMetric traceMetric : traceMetrics) {
            items.add(traceMetric.getSnapshot());
        }
        Ordering<Snapshot> byTotalOrdering = Ordering.natural().onResultOf(
                new Function<Snapshot, Long>() {
                    public Long apply(Snapshot input) {
                        return input.getTotal();
                    }
                });
        return gson.toJson(byTotalOrdering.reverse().sortedCopy(items));
    }

    @VisibleForTesting
    @Nullable
    static ByteStream getMergedStackTree(Trace trace) {
        MergedStackTreeNode rootNode = trace.getMergedStackTree().getRootNode();
        if (rootNode == null) {
            return null;
        }
        List<Object> toVisit = new ArrayList<Object>();
        toVisit.add(rootNode);
        return new MergedStackTreeByteStream(toVisit);
    }

    private static String getStackTraceJson(StackTraceElement[] stackTrace) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginArray();
        String realDescription = null;
        for (StackTraceElement stackTraceElement : stackTrace) {
            if (stackTraceElement.getMethodName().contains("$informant$")) {
                if (realDescription == null) {
                    realDescription = stackTraceElement.toString().replaceFirst(
                            "\\$informant\\$[^(]+\\(", "(");
                } else {
                    // this is an extra wrapping around the same method, e.g. for multiple metrics
                    // around the same method
                }
            } else {
                if (realDescription == null) {
                    jw.value(stackTraceElement.toString());
                } else {
                    jw.value(realDescription);
                    realDescription = null;
                }
            }
        }
        jw.endArray();
        jw.close();
        return sb.toString();
    }

    private static class SpansByteStream extends ByteStream {

        private static final int TARGET_CHUNK_SIZE = 8192;

        private final Iterator<Span> spans;
        private final long captureTick;
        private final StackTraceDao stackTraceDao;
        private final ByteArrayOutputStream baos;
        private final Writer raw;
        private final JsonWriter jw;
        private final Map<String, String> stackTraces = Maps.newHashMap();

        private SpansByteStream(Iterator<Span> spans, long captureTick,
                StackTraceDao stackTraceDao) throws IOException {

            this.spans = spans;
            this.captureTick = captureTick;
            this.stackTraceDao = stackTraceDao;
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
                // store the captured stack traces so they are available to anyone who receives the
                // spans byte stream
                stackTraceDao.storeStackTraces(stackTraces);
            }
            byte[] chunk = baos.toByteArray();
            baos.reset();
            return chunk;
        }

        // timings for traces that are still active are normalized to the capture tick in order to
        // *attempt* to present a picture of the trace at that exact tick
        // (without using synchronization to block updates to the trace while it is being read)
        private void writeSpan(Span span) throws IOException {
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
            jw.name("nestingLevel");
            jw.value(span.getNestingLevel());
            Supplier<Message> messageSupplier = span.getMessageSupplier();
            if (messageSupplier != null) {
                Message message = messageSupplier.get();
                jw.name("message");
                jw.beginObject();
                jw.name("text");
                String text;
                try {
                    text = message.getText();
                } catch (Throwable t) {
                    // getText() could be plugin provided, e.g. if not using TemplateMessage
                    text = "an error occurred calling getText() on " + message.getClass().getName();
                    logger.warn(text, t);
                }
                jw.value(text);
                Map<String, ?> detail = message.getDetail();
                if (detail != null && !detail.isEmpty()) {
                    jw.name("detail");
                    new MessageDetailSerializer(jw).write(detail);
                }
                jw.endObject();
            }
            ErrorMessage errorMessage = span.getErrorMessage();
            if (errorMessage != null) {
                jw.name("error");
                jw.beginObject();
                jw.name("text");
                jw.value(errorMessage.getText());
                Map<String, ?> detail = errorMessage.getDetail();
                if (detail != null) {
                    jw.name("detail");
                    new MessageDetailSerializer(jw).write(detail);
                }
                StackTraceElement[] stackTrace = errorMessage.getStackTrace();
                if (stackTrace != null) {
                    writeStackTraceHash(stackTrace, jw);
                }
                jw.endObject();
            }
            StackTraceElement[] stackTrace = span.getStackTrace();
            if (stackTrace != null) {
                writeStackTraceHash(stackTrace, jw);
            }
            jw.endObject();
        }

        private void writeStackTraceHash(StackTraceElement[] stackTrace, JsonWriter jw)
                throws IOException {

            String stackTraceJson = getStackTraceJson(stackTrace);
            String stackTraceHash = Hashing.sha1().hashString(stackTraceJson, Charsets.UTF_8)
                    .toString();
            stackTraces.put(stackTraceHash, stackTraceJson);
            jw.name("stackTraceHash");
            jw.value(stackTraceHash);
        }
    }

    private static class MergedStackTreeByteStream extends ByteStream {

        private static final int TARGET_CHUNK_SIZE = 8192;

        private static final Pattern metricMarkerMethodPattern = Pattern
                .compile("^.*\\$informant\\$metric\\$(.*)\\$[0-9]+$");

        private final List<Object> toVisit;
        private final ByteArrayOutputStream baos;
        private final JsonWriter jw;
        private final List<String> metricNameStack = Lists.newArrayList();

        private MergedStackTreeByteStream(List<Object> toVisit) {
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
            Object curr = toVisit.remove(toVisit.size() - 1);
            if (curr instanceof MergedStackTreeNode) {
                MergedStackTreeNode currNode = (MergedStackTreeNode) curr;
                jw.beginObject();
                toVisit.add(JsonWriterOp.END_OBJECT);
                if (currNode.isSyntheticRoot()) {
                    jw.name("stackTraceElement").value("<multiple root nodes>");
                } else {
                    jw.name("stackTraceElement").value(currNode.getStackTraceElement().toString());
                    String newMetricName = getMetricName(currNode.getStackTraceElement());
                    if (newMetricName != null && !newMetricName.equals(top(metricNameStack))) {
                        // filter out successive duplicates which are common from weaving groups of
                        // overloaded methods
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
                List<MergedStackTreeNode> childNodes = Lists.newArrayList(currNode.getChildNodes());
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

        @Nullable
        private static String top(List<String> stack) {
            if (stack.isEmpty()) {
                return null;
            } else {
                return stack.get(stack.size() - 1);
            }
        }

        @Nullable
        private static String getMetricName(StackTraceElement stackTraceElement) {
            return getMetricNameFromMethodName(stackTraceElement);
        }

        @Nullable
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
