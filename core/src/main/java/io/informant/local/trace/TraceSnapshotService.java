/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.trace;

import io.informant.api.CapturedException;
import io.informant.api.ErrorMessage;
import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.api.Message;
import io.informant.api.MessageSupplier;
import io.informant.core.config.ConfigService;
import io.informant.core.config.GeneralConfig;
import io.informant.core.trace.MergedStackTree;
import io.informant.core.trace.MergedStackTree.StackTraceElementPlus;
import io.informant.core.trace.MergedStackTreeNode;
import io.informant.core.trace.Span;
import io.informant.core.trace.Trace;
import io.informant.core.trace.TraceMetric.Snapshot;
import io.informant.core.util.ByteStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
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

    private final ConfigService configService;

    @Inject
    TraceSnapshotService(ConfigService configService) {
        this.configService = configService;
    }

    public TraceSnapshot from(Trace trace, long captureTick) throws IOException {
        return from(trace, captureTick, true);
    }

    public TraceSnapshot from(Trace trace, long captureTick, boolean includeDetail)
            throws IOException {
        TraceSnapshot.Builder builder = TraceSnapshot.builder();
        builder.id(trace.getId());
        builder.startAt(trace.getStartAt());
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
        builder.background(trace.isBackground());
        builder.headline(trace.getHeadline());
        ErrorMessage errorMessage = trace.getRootSpan().getErrorMessage();
        if (errorMessage != null) {
            builder.errorText(errorMessage.getText());
            Map<String, ? extends /*@Nullable*/Object> detail = errorMessage.getDetail();
            if (detail != null) {
                StringBuilder sb = new StringBuilder();
                JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
                new MessageDetailSerializer(jw).write(detail);
                jw.close();
                builder.errorDetail(sb.toString());
            }
            CapturedException exception = errorMessage.getException();
            if (exception != null) {
                builder.exception(getExceptionJson(exception));
            }
        }
        Map<String, Optional<String>> attributes = trace.getAttributes();
        if (!attributes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
            jw.beginObject();
            for (Entry<String, Optional<String>> entry : attributes.entrySet()) {
                jw.name(entry.getKey());
                jw.value(entry.getValue().orNull());
            }
            jw.endObject();
            jw.close();
            builder.attributes(sb.toString());
        }
        builder.userId(trace.getUserId());
        builder.metrics(getMetricsJson(trace));
        if (includeDetail) {
            SpansByteStream spansByteStream = new SpansByteStream(trace.getSpans().iterator(),
                    captureTick);
            builder.spans(spansByteStream);
            builder.coarseMergedStackTree(TraceSnapshotService.getMergedStackTree(trace
                    .getCoarseMergedStackTree()));
            builder.fineMergedStackTree(TraceSnapshotService.getMergedStackTree(trace
                    .getFineMergedStackTree()));
        }
        return builder.build();
    }
    public boolean shouldStore(Trace trace) {
        if (trace.isStuck() || trace.isError()) {
            return true;
        }
        long duration = trace.getDuration();
        // check if should store for user tracing
        String userId = trace.getUserId();
        if (userId != null
                && userId.equals(configService.getUserConfig().getUserId())
                && duration >= TimeUnit.MILLISECONDS.toNanos(configService.getUserConfig()
                        .getStoreThresholdMillis())) {
            return true;
        }
        // check if should store for fine profiling
        if (trace.isFine()) {
            int fineStoreThresholdMillis = configService.getFineProfilingConfig()
                    .getStoreThresholdMillis();
            if (fineStoreThresholdMillis != GeneralConfig.STORE_THRESHOLD_DISABLED) {
                return trace.getDuration() >= TimeUnit.MILLISECONDS
                        .toNanos(fineStoreThresholdMillis);
            }
        }
        // fall back to core store threshold
        return shouldStoreBasedOnCoreStoreThreshold(trace);
    }

    private boolean shouldStoreBasedOnCoreStoreThreshold(Trace trace) {
        int storeThresholdMillis = configService.getGeneralConfig().getStoreThresholdMillis();
        return storeThresholdMillis != GeneralConfig.STORE_THRESHOLD_DISABLED
                && trace.getDuration() >= TimeUnit.MILLISECONDS.toNanos(storeThresholdMillis);
    }

    public static ByteStream toByteStream(TraceSnapshot snapshot, boolean active)
            throws UnsupportedEncodingException {
        List<ByteStream> byteStreams = Lists.newArrayList();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"");
        sb.append(snapshot.getId());
        sb.append("\",\"start\":");
        sb.append(snapshot.getStartAt());
        sb.append(",\"duration\":");
        sb.append(snapshot.getDuration());
        sb.append(",\"active\":");
        sb.append(active);
        sb.append(",\"stuck\":");
        sb.append(snapshot.isStuck());
        sb.append(",\"completed\":");
        sb.append(snapshot.isCompleted());
        sb.append(",\"background\":");
        sb.append(snapshot.isBackground());
        sb.append(",\"headline\":");
        sb.append(escapeJson(snapshot.getHeadline()));
        String attributes = snapshot.getAttributes();
        if (attributes != null) {
            sb.append(",\"attributes\":");
            sb.append(attributes);
        }
        String userId = snapshot.getUserId();
        if (userId != null) {
            sb.append(",\"userId\":");
            sb.append(escapeJson(userId));
        }
        String errorText = snapshot.getErrorText();
        if (errorText != null) {
            sb.append(",\"error\":{\"text\":");
            sb.append(escapeJson(errorText));
            if (snapshot.getErrorDetail() != null) {
                sb.append(",\"detail\":");
                sb.append(snapshot.getErrorDetail());
            }
            if (snapshot.getException() != null) {
                sb.append(",\"exception\":");
                sb.append(snapshot.getException());
            }
            sb.append("}");
        }
        String metrics = snapshot.getMetrics();
        if (metrics != null) {
            sb.append(",\"metrics\":");
            sb.append(metrics);
        }
        ByteStream spans = snapshot.getSpans();
        if (spans != null) {
            sb.append(",\"spans\":");
            // flush current StringBuilder as its own chunk and reset StringBuilder
            byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
            sb.setLength(0);
            byteStreams.add(spans);
        }
        ByteStream coarseMergedStackTree = snapshot.getCoarseMergedStackTree();
        if (coarseMergedStackTree != null) {
            sb.append(",\"coarseMergedStackTree\":");
            // flush current StringBuilder as its own chunk and reset StringBuilder
            byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
            sb.setLength(0);
            byteStreams.add(coarseMergedStackTree);
        }
        ByteStream fineMergedStackTree = snapshot.getFineMergedStackTree();
        if (fineMergedStackTree != null) {
            sb.append(",\"fineMergedStackTree\":");
            // flush current StringBuilder as its own chunk and reset StringBuilder
            byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
            sb.setLength(0);
            byteStreams.add(fineMergedStackTree);
        }
        sb.append("}");
        // flush current StringBuilder as its own chunk
        byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
        return ByteStream.of(byteStreams);
    }

    // this feels more performant than gson.toJson(s)
    private static String escapeJson(@Nullable String s) {
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
        List<Snapshot> items = trace.getTraceMetricSnapshots();
        Ordering<Snapshot> byTotalOrdering = Ordering.natural().onResultOf(
                new Function<Snapshot, Long>() {
                    public Long apply(@Nullable Snapshot snapshot) {
                        if (snapshot == null) {
                            throw new NullPointerException("Ordering of non-null elements only");
                        }
                        return snapshot.getTotal();
                    }
                });
        return gson.toJson(byTotalOrdering.reverse().sortedCopy(items));
    }

    @VisibleForTesting
    @Nullable
    static ByteStream getMergedStackTree(@Nullable MergedStackTree mergedStackTree) {
        if (mergedStackTree == null) {
            return null;
        }
        MergedStackTreeNode rootNode = mergedStackTree.getRootNode();
        if (rootNode == null) {
            return null;
        }
        List<Object> toVisit = new ArrayList<Object>();
        toVisit.add(rootNode);
        return new MergedStackTreeByteStream(toVisit);
    }

    public static String getExceptionJson(CapturedException exception) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        writeException(exception, jw);
        jw.close();
        return sb.toString();
    }

    private static void writeException(CapturedException exception, JsonWriter jw)
            throws IOException {
        jw.beginObject();
        jw.name("display");
        jw.value(exception.getDisplay());
        jw.name("stackTrace");
        writeStackTrace(exception.getStackTrace(), jw);
        jw.name("framesInCommonWithCaused");
        jw.value(exception.getFramesInCommonWithCaused());
        CapturedException cause = exception.getCause();
        if (cause != null) {
            jw.name("cause");
            writeException(cause, jw);
        }
        jw.endObject();
    }

    private static void writeStackTrace(StackTraceElement[] stackTrace, JsonWriter jw)
            throws IOException {
        jw.beginArray();
        List<StackTraceElementPlus> elements = MergedStackTree
                .stripSyntheticMetricMethods(stackTrace);
        for (StackTraceElementPlus element : elements) {
            jw.value(element.getStackTraceElement().toString());
        }
        jw.endArray();
    }

    private static class SpansByteStream extends ByteStream {

        private static final int TARGET_CHUNK_SIZE = 8192;

        @ReadOnly
        private final Iterator<Span> spans;
        private final long captureTick;
        private final ByteArrayOutputStream baos;
        private final Writer raw;
        private final JsonWriter jw;

        private SpansByteStream(@ReadOnly Iterator<Span> spans, long captureTick)
                throws IOException {
            this.spans = spans;
            this.captureTick = captureTick;
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
            MessageSupplier messageSupplier = span.getMessageSupplier();
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
                Map<String, ? extends /*@Nullable*/Object> detail = message.getDetail();
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
                Map<String, ? extends /*@Nullable*/Object> errorDetail = errorMessage.getDetail();
                if (errorDetail != null) {
                    jw.name("detail");
                    new MessageDetailSerializer(jw).write(errorDetail);
                }
                CapturedException exception = errorMessage.getException();
                if (exception != null) {
                    jw.name("exception");
                    writeException(exception, jw);
                }
                jw.endObject();
            }
            StackTraceElement[] stackTrace = span.getStackTrace();
            if (stackTrace != null) {
                jw.name("stackTrace");
                writeStackTrace(stackTrace, jw);
            }
            jw.endObject();
        }
    }

    private static class MergedStackTreeByteStream extends ByteStream {

        private static final int TARGET_CHUNK_SIZE = 8192;

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
                StackTraceElement stackTraceElement = currNode.getStackTraceElement();
                if (stackTraceElement == null) {
                    jw.name("stackTraceElement").value("<multiple root nodes>");
                } else {
                    jw.name("stackTraceElement").value(stackTraceElement.toString());
                    Collection<String> currMetricNames = currNode.getMetricNames();
                    for (String currMetricName : currMetricNames) {
                        if (metricNameStack.isEmpty() || !currMetricName.equals(
                                metricNameStack.get(metricNameStack.size() - 1))) {
                            // filter out successive duplicates which are common from weaving groups
                            // of overloaded methods
                            metricNameStack.add(currMetricName);
                            toVisit.add(JsonWriterOp.POP_METRIC_NAME);
                        }
                    }
                    jw.name("sampleCount").value(currNode.getSampleCount());
                    State leafThreadState = currNode.getLeafThreadState();
                    if (leafThreadState != null) {
                        jw.name("leafThreadState").value(leafThreadState.name());
                        jw.name("metricNames");
                        jw.beginArray();
                        for (String metricName : metricNameStack) {
                            jw.value(metricName);
                        }
                        jw.endArray();
                    }
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

        @Immutable
        private static enum JsonWriterOp {
            END_OBJECT, END_ARRAY, POP_METRIC_NAME;
        }
    }
}
