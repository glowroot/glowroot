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
package io.informant.local.store;

import io.informant.api.MessageSupplier;
import io.informant.api.internal.ExceptionInfo;
import io.informant.api.internal.ReadableErrorMessage;
import io.informant.api.internal.ReadableMessage;
import io.informant.core.MergedStackTree;
import io.informant.core.MergedStackTree.StackTraceElementPlus;
import io.informant.core.MergedStackTreeNode;
import io.informant.core.Span;
import io.informant.core.Trace;
import io.informant.core.Trace.TraceAttribute;
import io.informant.core.TraceMetric.Snapshot;
import io.informant.util.CharArrayWriter;
import io.informant.util.GsonFactory;
import io.informant.util.NotThreadSafe;
import io.informant.util.Static;
import io.informant.util.ThreadSafe;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.State;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class TraceWriter {

    private static final Logger logger = LoggerFactory.getLogger(TraceWriter.class);
    private static final Gson gson = GsonFactory.create();

    private TraceWriter() {}

    public static TraceSnapshot toTraceSnapshot(Trace trace, long captureTick, boolean summary)
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
        ReadableErrorMessage errorMessage = trace.getRootSpan().getErrorMessage();
        if (errorMessage != null) {
            builder.errorText(errorMessage.getText());
            builder.errorDetail(getErrorDetailJson(errorMessage.getDetail()));
            builder.exception(getExceptionJson(errorMessage.getExceptionInfo()));
        }
        builder.attributes(getAttributesJson(trace));
        builder.userId(trace.getUserId());
        builder.metrics(getMetricsJson(trace));
        if (!summary) {
            builder.spans(new SpansCharSource(trace.getSpans().iterator(), captureTick));
            builder.coarseMergedStackTree(getMergedStackTree(trace.getCoarseMergedStackTree()));
            builder.fineMergedStackTree(getMergedStackTree(trace.getFineMergedStackTree()));
        }
        return builder.build();
    }

    @Nullable
    private static String getErrorDetailJson(
            @ReadOnly @Nullable Map<String, ? extends /*@Nullable*/Object> detail)
            throws IOException {
        if (detail == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        new MessageDetailSerializer(jw).write(detail);
        jw.close();
        return sb.toString();
    }

    @Nullable
    private static String getExceptionJson(@Nullable ExceptionInfo exception) throws IOException {
        if (exception == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        writeException(exception, jw);
        jw.close();
        return sb.toString();
    }

    @Nullable
    private static String getAttributesJson(Trace trace) throws IOException {
        List<TraceAttribute> attributes = trace.getAttributes();
        if (attributes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginObject();
        for (TraceAttribute attribute : attributes) {
            jw.name(attribute.getName());
            jw.value(attribute.getValue());
        }
        jw.endObject();
        jw.close();
        return sb.toString();
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
    static CharSource getMergedStackTree(@Nullable MergedStackTree mergedStackTree) {
        if (mergedStackTree == null) {
            return null;
        }
        synchronized (mergedStackTree.getLock()) {
            MergedStackTreeNode rootNode = mergedStackTree.getRootNode();
            if (rootNode == null) {
                return null;
            }
            // need to convert merged stack tree into bytes entirely inside of the above lock
            // (no lazy CharSource)
            StringWriter sw = new StringWriter(32768);
            try {
                new MergedStackTreeWriter(rootNode, sw).write();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                return null;
            }
            return CharStreams.asCharSource(sw.toString());
        }
    }

    private static void writeException(ExceptionInfo exception, JsonWriter jw) throws IOException {
        jw.beginObject();
        jw.name("display");
        jw.value(exception.getDisplay());
        jw.name("stackTrace");
        writeStackTrace(exception.getStackTrace(), jw);
        jw.name("framesInCommonWithCaused");
        jw.value(exception.getFramesInCommonWithCaused());
        ExceptionInfo cause = exception.getCause();
        if (cause != null) {
            jw.name("cause");
            writeException(cause, jw);
        }
        jw.endObject();
    }

    private static void writeStackTrace(@ReadOnly List<StackTraceElement> stackTrace, JsonWriter jw)
            throws IOException {
        jw.beginArray();
        List<StackTraceElementPlus> elements = MergedStackTree
                .stripSyntheticMetricMethods(stackTrace);
        for (StackTraceElementPlus element : elements) {
            jw.value(element.getStackTraceElement().toString());
        }
        jw.endArray();
    }

    @ThreadSafe
    private static class SpansCharSource extends CharSource {

        @ReadOnly
        private final Iterator<Span> spans;
        private final long captureTick;

        private SpansCharSource(@ReadOnly Iterator<Span> spans, long captureTick) {
            this.spans = spans;
            this.captureTick = captureTick;
        }

        @Override
        public Reader openStream() throws IOException {
            return new SpansReader(spans, captureTick);
        }
    }

    @NotThreadSafe
    private static class SpansReader extends Reader {

        @ReadOnly
        private final Iterator<Span> spans;
        private final long captureTick;
        private final CharArrayWriter writer;
        private final JsonWriter jw;

        private int writerIndex;
        private boolean limitExceeded;

        private SpansReader(@ReadOnly Iterator<Span> spans, long captureTick) throws IOException {
            this.spans = spans;
            this.captureTick = captureTick;
            writer = new CharArrayWriter();
            jw = new JsonWriter(writer);
            jw.beginArray();
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            int writerRemaining = writer.size() - writerIndex;
            if (writerRemaining > 0) {
                int nChars = Math.min(len, writerRemaining);
                writer.arraycopy(writerIndex, cbuf, off, nChars);
                writerIndex += nChars;
                return nChars;
            }
            // need to add another span to writer
            if (!spans.hasNext()) {
                return -1;
            }
            writer.reset();
            writerIndex = 0;
            // note it is possible for writeSpan() to not write anything
            writeSpan(spans.next());
            if (!spans.hasNext()) {
                jw.endArray();
                jw.close();
            }
            // now go back and read the new data
            return read(cbuf, off, len);
        }

        @Override
        public void close() {}

        // timings for traces that are still active are normalized to the capture tick in order to
        // *attempt* to present a picture of the trace at that exact tick
        // (without using synchronization to block updates to the trace while it is being read)
        private void writeSpan(Span span) throws IOException {
            if (span.getStartTick() > captureTick) {
                // this span started after the capture tick
                return;
            }
            if (span.isLimitExceededMarker()) {
                limitExceeded = true;
                jw.beginObject();
                jw.name("limitExceededMarker");
                jw.value(true);
                jw.endObject();
                return;
            }
            if (span.isLimitExtendedMarker()) {
                limitExceeded = false;
                jw.beginObject();
                jw.name("limitExtendedMarker");
                jw.value(true);
                jw.endObject();
                return;
            }
            jw.beginObject();
            if (limitExceeded) {
                jw.name("extraError");
                jw.value(true);
            }
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
            jw.name("nestingLevel");
            jw.value(span.getNestingLevel());
            MessageSupplier messageSupplier = span.getMessageSupplier();
            if (messageSupplier != null) {
                jw.name("message");
                writeMessage((ReadableMessage) messageSupplier.get());
            }
            ReadableErrorMessage errorMessage = span.getErrorMessage();
            if (errorMessage != null) {
                jw.name("error");
                writeErrorMessage(errorMessage);
            }
            List<StackTraceElement> stackTrace = span.getStackTrace();
            if (stackTrace != null) {
                jw.name("stackTrace");
                writeStackTrace(stackTrace, jw);
            }
            jw.endObject();
        }

        private void writeMessage(ReadableMessage message) throws IOException {
            jw.beginObject();
            jw.name("text");
            jw.value(message.getText());
            Map<String, ? extends /*@Nullable*/Object> detail = message.getDetail();
            if (!detail.isEmpty()) {
                jw.name("detail");
                new MessageDetailSerializer(jw).write(detail);
            }
            jw.endObject();
        }

        private void writeErrorMessage(ReadableErrorMessage errorMessage) throws IOException {
            jw.beginObject();
            jw.name("text");
            jw.value(errorMessage.getText());
            Map<String, ? extends /*@Nullable*/Object> errorDetail = errorMessage.getDetail();
            if (errorDetail != null) {
                jw.name("detail");
                new MessageDetailSerializer(jw).write(errorDetail);
            }
            ExceptionInfo exception = errorMessage.getExceptionInfo();
            if (exception != null) {
                jw.name("exception");
                writeException(exception, jw);
            }
            jw.endObject();
        }
    }

    private static class MergedStackTreeWriter {

        private final List<Object> toVisit;
        private final JsonWriter jw;
        private final List<String> metricNameStack = Lists.newArrayList();

        private MergedStackTreeWriter(MergedStackTreeNode rootNode, Writer writer) {
            this.toVisit = Lists.newArrayList((Object) rootNode);
            jw = new JsonWriter(writer);
        }

        private void write() throws IOException {
            while (!toVisit.isEmpty()) {
                writeNext();
            }
            jw.close();
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
                    writeStackTraceElement(stackTraceElement, currNode);
                }
                List<MergedStackTreeNode> childNodes = currNode.getChildNodes();
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

        private void writeStackTraceElement(StackTraceElement stackTraceElement,
                MergedStackTreeNode currNode) throws IOException {
            jw.name("stackTraceElement").value(stackTraceElement.toString());
            List<String> currMetricNames = currNode.getMetricNames();
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
                writeLeaf(leafThreadState);
            }
        }

        private void writeLeaf(State leafThreadState) throws IOException {
            jw.name("leafThreadState").value(leafThreadState.name());
            jw.name("metricNames");
            jw.beginArray();
            for (String metricName : metricNameStack) {
                jw.value(metricName);
            }
            jw.endArray();
        }

        @Immutable
        private static enum JsonWriterOp {
            END_OBJECT, END_ARRAY, POP_METRIC_NAME;
        }
    }
}
