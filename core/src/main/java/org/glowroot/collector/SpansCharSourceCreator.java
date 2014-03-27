/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.collector;

import java.io.IOException;
import java.io.Reader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.io.CharSource;

import org.glowroot.api.MessageSupplier;
import org.glowroot.api.internal.ExceptionInfo;
import org.glowroot.api.internal.ReadableErrorMessage;
import org.glowroot.api.internal.ReadableMessage;
import org.glowroot.markers.NotThreadSafe;
import org.glowroot.markers.Static;
import org.glowroot.trace.model.MergedStackTree;
import org.glowroot.trace.model.MergedStackTree.StackTraceElementPlus;
import org.glowroot.trace.model.Span;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class SpansCharSourceCreator {

    @ReadOnly
    private static final JsonFactory jsonFactory = new JsonFactory();

    private SpansCharSourceCreator() {}

    public static CharSource createSpansCharSource(Iterable<Span> spans, long captureTick) {
        return new SpansCharSource(spans, captureTick);
    }

    private static void writeStackTrace(@ReadOnly List<StackTraceElement> stackTrace,
            JsonGenerator jw) throws IOException {
        jw.writeStartArray();
        List<StackTraceElementPlus> elements =
                MergedStackTree.stripSyntheticMetricMethods(stackTrace);
        for (StackTraceElementPlus element : elements) {
            jw.writeString(element.getStackTraceElement().toString());
        }
        jw.writeEndArray();
    }

    @Immutable
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

        private SpansReader(@ReadOnly Iterator<Span> spans, long captureTick) throws IOException {
            this.spans = spans;
            this.captureTick = captureTick;
            writer = new CharArrayWriter();
            jg = jsonFactory.createGenerator(writer);
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
                jg.writeStartObject();
                jg.writeBooleanField("limitExceededMarker", true);
                jg.writeEndObject();
                return;
            }
            if (span.isLimitExtendedMarker()) {
                jg.writeStartObject();
                jg.writeBooleanField("limitExtendedMarker", true);
                jg.writeEndObject();
                return;
            }
            jg.writeStartObject();
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
                new DetailMapWriter(jg).write(detail);
            }
            jg.writeEndObject();
        }

        private void writeErrorMessage(ReadableErrorMessage errorMessage) throws IOException {
            jg.writeStartObject();
            jg.writeStringField("text", errorMessage.getText());
            Map<String, ? extends /*@Nullable*/Object> errorDetail = errorMessage.getDetail();
            if (errorDetail != null) {
                jg.writeFieldName("detail");
                new DetailMapWriter(jg).write(errorDetail);
            }
            ExceptionInfo exception = errorMessage.getExceptionInfo();
            if (exception != null) {
                jg.writeFieldName("exception");
                writeException(exception, jg);
            }
            jg.writeEndObject();
        }

        private void writeException(ExceptionInfo exception, JsonGenerator jg) throws IOException {
            jg.writeStartObject();
            jg.writeStringField("display", exception.getDisplay());
            jg.writeFieldName("stackTrace");
            writeStackTrace(exception.getStackTrace(), jg);
            jg.writeNumberField("framesInCommonWithCaused",
                    exception.getFramesInCommonWithCaused());
            ExceptionInfo cause = exception.getCause();
            if (cause != null) {
                jg.writeFieldName("cause");
                writeException(cause, jg);
            }
            jg.writeEndObject();
        }
    }

    @NotThreadSafe
    private static class CharArrayWriter extends java.io.CharArrayWriter {

        // provides access to protected char buffer
        public void arraycopy(int srcPos, char[] dest, int destPos, int length) {
            System.arraycopy(buf, srcPos, dest, destPos, length);
        }
    }
}
