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
import io.informant.util.NotThreadSafe;
import io.informant.util.ObjectMappers;
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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class TraceWriter {

    private static final Logger logger = LoggerFactory.getLogger(TraceWriter.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private TraceWriter() {}

    public static TraceSnapshot toTraceSnapshot(Trace trace, long captureTick, boolean summary)
            throws IOException {
        TraceSnapshot.Builder builder = TraceSnapshot.builder();
        builder.id(trace.getId());
        builder.start(trace.getStart());
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
            builder.errorDetail(writeErrorDetailAsString(errorMessage.getDetail()));
            builder.exception(writeExceptionInfoAsString(errorMessage.getExceptionInfo()));
        }
        builder.attributes(writeAttributesAsString(trace.getAttributes()));
        builder.userId(trace.getUserId());
        builder.metrics(writeMetricsAsString(trace.getTraceMetricSnapshots()));
        if (!summary) {
            builder.spans(new SpansCharSource(trace.getSpans(), captureTick));
            builder.coarseMergedStackTree(createCharSource(trace.getCoarseMergedStackTree()));
            builder.fineMergedStackTree(createCharSource(trace.getFineMergedStackTree()));
        }
        return builder.build();
    }

    @Nullable
    private static String writeErrorDetailAsString(
            @ReadOnly @Nullable Map<String, ? extends /*@Nullable*/Object> errorDetail)
            throws IOException {
        if (errorDetail == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        new MessageDetailSerializer(jg).write(errorDetail);
        jg.close();
        return sb.toString();
    }

    @Nullable
    private static String writeExceptionInfoAsString(@Nullable ExceptionInfo exceptionInfo)
            throws IOException {
        if (exceptionInfo == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        writeException(exceptionInfo, jg);
        jg.close();
        return sb.toString();
    }

    @Nullable
    private static String writeAttributesAsString(List<TraceAttribute> attributes)
            throws IOException {
        if (attributes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        for (TraceAttribute attribute : attributes) {
            jg.writeStringField(attribute.getName(), attribute.getValue());
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @Nullable
    private static String writeMetricsAsString(List<Snapshot> items)
            throws JsonProcessingException {
        Ordering<Snapshot> byTotalOrdering = Ordering.natural().onResultOf(
                new Function<Snapshot, Long>() {
                    public Long apply(@Nullable Snapshot snapshot) {
                        if (snapshot == null) {
                            throw new NullPointerException("Ordering of non-null elements only");
                        }
                        return snapshot.getTotal();
                    }
                });
        return mapper.writeValueAsString(byTotalOrdering.reverse().sortedCopy(items));
    }

    @VisibleForTesting
    @Nullable
    static CharSource createCharSource(@Nullable MergedStackTree mergedStackTree) {
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

    private static void writeException(ExceptionInfo exception, JsonGenerator jg)
            throws IOException {
        jg.writeStartObject();
        jg.writeStringField("display", exception.getDisplay());
        jg.writeFieldName("stackTrace");
        writeStackTrace(exception.getStackTrace(), jg);
        jg.writeNumberField("framesInCommonWithCaused", exception.getFramesInCommonWithCaused());
        ExceptionInfo cause = exception.getCause();
        if (cause != null) {
            jg.writeFieldName("cause");
            writeException(cause, jg);
        }
        jg.writeEndObject();
    }

    private static void writeStackTrace(@ReadOnly List<StackTraceElement> stackTrace,
            JsonGenerator jw)
            throws IOException {
        jw.writeStartArray();
        List<StackTraceElementPlus> elements =
                MergedStackTree.stripSyntheticMetricMethods(stackTrace);
        for (StackTraceElementPlus element : elements) {
            jw.writeString(element.getStackTraceElement().toString());
        }
        jw.writeEndArray();
    }

    @ThreadSafe
    private static class SpansCharSource extends CharSource {

        @ReadOnly
        private final Iterable<Span> spans;
        private final long captureTick;

        private SpansCharSource(@ReadOnly Iterable<Span> spans, long captureTick) {
            this.spans = spans;
            this.captureTick = captureTick;
        }

        @Override
        public Reader openStream() throws IOException {
            return new SpansReader(spans.iterator(), captureTick);
        }
    }

    @NotThreadSafe
    private static class SpansReader extends Reader {

        @ReadOnly
        private final Iterator<Span> spans;
        private final long captureTick;
        private final CharArrayWriter writer;
        private final JsonGenerator jg;

        private int writerIndex;
        private boolean limitExceeded;

        private SpansReader(@ReadOnly Iterator<Span> spans, long captureTick) throws IOException {
            this.spans = spans;
            this.captureTick = captureTick;
            writer = new CharArrayWriter();
            jg = mapper.getFactory().createGenerator(writer);
            jg.writeStartArray();
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
                jg.writeEndArray();
                jg.close();
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
                jg.writeStartObject();
                jg.writeBooleanField("limitExceededMarker", true);
                jg.writeEndObject();
                return;
            }
            if (span.isLimitExtendedMarker()) {
                limitExceeded = false;
                jg.writeStartObject();
                jg.writeBooleanField("limitExtendedMarker", true);
                jg.writeEndObject();
                return;
            }
            jg.writeStartObject();
            if (limitExceeded) {
                jg.writeBooleanField("extraError", true);
            }
            jg.writeNumberField("offset", span.getOffset());
            jg.writeFieldName("duration");
            long endTick = span.getEndTick();
            if (endTick != 0 && endTick <= captureTick) {
                jg.writeNumber(span.getEndTick() - span.getStartTick());
            } else {
                jg.writeNumber(captureTick - span.getStartTick());
                jg.writeBooleanField("active", true);
            }
            jg.writeNumberField("nestingLevel", span.getNestingLevel());
            MessageSupplier messageSupplier = span.getMessageSupplier();
            if (messageSupplier != null) {
                jg.writeFieldName("message");
                writeMessage((ReadableMessage) messageSupplier.get());
            }
            ReadableErrorMessage errorMessage = span.getErrorMessage();
            if (errorMessage != null) {
                jg.writeFieldName("error");
                writeErrorMessage(errorMessage);
            }
            List<StackTraceElement> stackTrace = span.getStackTrace();
            if (stackTrace != null) {
                jg.writeFieldName("stackTrace");
                writeStackTrace(stackTrace, jg);
            }
            jg.writeEndObject();
        }

        private void writeMessage(ReadableMessage message) throws IOException {
            jg.writeStartObject();
            jg.writeStringField("text", message.getText());
            Map<String, ? extends /*@Nullable*/Object> detail = message.getDetail();
            if (!detail.isEmpty()) {
                jg.writeFieldName("detail");
                new MessageDetailSerializer(jg).write(detail);
            }
            jg.writeEndObject();
        }

        private void writeErrorMessage(ReadableErrorMessage errorMessage) throws IOException {
            jg.writeStartObject();
            jg.writeStringField("text", errorMessage.getText());
            Map<String, ? extends /*@Nullable*/Object> errorDetail = errorMessage.getDetail();
            if (errorDetail != null) {
                jg.writeFieldName("detail");
                new MessageDetailSerializer(jg).write(errorDetail);
            }
            ExceptionInfo exception = errorMessage.getExceptionInfo();
            if (exception != null) {
                jg.writeFieldName("exception");
                writeException(exception, jg);
            }
            jg.writeEndObject();
        }
    }

    private static class MergedStackTreeWriter {

        private final List<Object> toVisit;
        private final JsonGenerator jg;
        private final List<String> metricNameStack = Lists.newArrayList();

        private MergedStackTreeWriter(MergedStackTreeNode rootNode, Writer writer)
                throws IOException {
            this.toVisit = Lists.newArrayList((Object) rootNode);
            jg = mapper.getFactory().createGenerator(writer);
        }

        private void write() throws IOException {
            while (!toVisit.isEmpty()) {
                writeNext();
            }
            jg.close();
        }

        private void writeNext() throws IOException {
            Object curr = toVisit.remove(toVisit.size() - 1);
            if (curr instanceof MergedStackTreeNode) {
                MergedStackTreeNode currNode = (MergedStackTreeNode) curr;
                jg.writeStartObject();
                toVisit.add(JsonGeneratorOp.END_OBJECT);
                StackTraceElement stackTraceElement = currNode.getStackTraceElement();
                if (stackTraceElement == null) {
                    jg.writeStringField("stackTraceElement", "<multiple root nodes>");
                } else {
                    writeStackTraceElement(stackTraceElement, currNode);
                }
                List<MergedStackTreeNode> childNodes = currNode.getChildNodes();
                if (!childNodes.isEmpty()) {
                    jg.writeArrayFieldStart("childNodes");
                    toVisit.add(JsonGeneratorOp.END_ARRAY);
                    toVisit.addAll(Lists.reverse(childNodes));
                }
            } else if (curr == JsonGeneratorOp.END_ARRAY) {
                jg.writeEndArray();
            } else if (curr == JsonGeneratorOp.END_OBJECT) {
                jg.writeEndObject();
            } else if (curr == JsonGeneratorOp.POP_METRIC_NAME) {
                metricNameStack.remove(metricNameStack.size() - 1);
            }
        }

        private void writeStackTraceElement(StackTraceElement stackTraceElement,
                MergedStackTreeNode currNode) throws IOException {
            jg.writeStringField("stackTraceElement", stackTraceElement.toString());
            List<String> currMetricNames = currNode.getMetricNames();
            for (String currMetricName : currMetricNames) {
                if (metricNameStack.isEmpty() || !currMetricName.equals(
                        metricNameStack.get(metricNameStack.size() - 1))) {
                    // filter out successive duplicates which are common from weaving groups
                    // of overloaded methods
                    metricNameStack.add(currMetricName);
                    toVisit.add(JsonGeneratorOp.POP_METRIC_NAME);
                }
            }
            jg.writeNumberField("sampleCount", currNode.getSampleCount());
            State leafThreadState = currNode.getLeafThreadState();
            if (leafThreadState != null) {
                writeLeaf(leafThreadState);
            }
        }

        private void writeLeaf(State leafThreadState) throws IOException {
            jg.writeStringField("leafThreadState", leafThreadState.name());
            jg.writeArrayFieldStart("metricNames");
            for (String metricName : metricNameStack) {
                jg.writeString(metricName);
            }
            jg.writeEndArray();
        }

        @Immutable
        private static enum JsonGeneratorOp {
            END_OBJECT, END_ARRAY, POP_METRIC_NAME;
        }
    }
}
