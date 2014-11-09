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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharSource;

import org.glowroot.api.MessageSupplier;
import org.glowroot.api.internal.ExceptionInfo;
import org.glowroot.api.internal.ReadableErrorMessage;
import org.glowroot.api.internal.ReadableMessage;
import org.glowroot.common.Ticker;
import org.glowroot.transaction.model.Profile;
import org.glowroot.transaction.model.Profile.StackTraceElementPlus;
import org.glowroot.transaction.model.TraceEntry;

public class EntriesCharSourceCreator {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private EntriesCharSourceCreator() {}

    public static CharSource createEntriesCharSource(List<TraceEntry> entries,
            long transactionStartTick, long captureTick) {
        return new EntriesCharSource(entries, transactionStartTick, captureTick);
    }

    private static void writeStackTrace(List<StackTraceElement> stackTrace, JsonGenerator jw)
            throws IOException {
        jw.writeStartArray();
        List<StackTraceElementPlus> elements = Profile.stripSyntheticMetricMethods(stackTrace);
        for (StackTraceElementPlus element : elements) {
            jw.writeString(element.getStackTraceElement().toString());
        }
        jw.writeEndArray();
    }

    private static class EntriesCharSource extends CharSource {

        private final List<TraceEntry> entries;
        private final long transactionStartTick;
        private final long captureTick;

        private EntriesCharSource(List<TraceEntry> entries, long transactionStartTick,
                long captureTick) {
            this.entries = entries;
            this.transactionStartTick = transactionStartTick;
            this.captureTick = captureTick;
        }

        @Override
        public Reader openStream() throws IOException {
            return new EntriesReader(entries, transactionStartTick, captureTick);
        }
    }

    private static class EntriesReader extends Reader {

        private final Iterator<TraceEntry> entries;
        private final long transactionStartTick;
        private final long captureTick;
        private final CharArrayWriter writer;
        private final JsonGenerator jg;

        private int writerIndex;

        private EntriesReader(List<TraceEntry> entries, long transactionStartTick, long captureTick)
                throws IOException {
            this.entries = entries.iterator();
            this.transactionStartTick = transactionStartTick;
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
            // need to add another entry to writer
            if (!entries.hasNext()) {
                return -1;
            }
            writer.reset();
            writerIndex = 0;
            // note it is possible for writeEntry() to not write anything
            writeEntry(entries.next());
            if (!entries.hasNext()) {
                jg.writeEndArray();
                jg.close();
            }
            // now go back and read the new data
            return read(cbuf, off, len);
        }

        @Override
        public void close() {}

        // timings for transactions that are still active are normalized to the capture tick in
        // order to *attempt* to present a picture of the transaction at that exact tick
        // (without using synchronization to block updates to the transaction while it is being
        // read)
        private void writeEntry(TraceEntry traceEntry) throws IOException {
            if (!Ticker.lessThanOrEqual(traceEntry.getStartTick(), captureTick)) {
                // this entry started after the capture tick
                return;
            }
            if (traceEntry.isLimitExceededMarker()) {
                jg.writeStartObject();
                jg.writeBooleanField("limitExceededMarker", true);
                jg.writeEndObject();
                return;
            }
            if (traceEntry.isLimitExtendedMarker()) {
                jg.writeStartObject();
                jg.writeBooleanField("limitExtendedMarker", true);
                jg.writeEndObject();
                return;
            }
            jg.writeStartObject();
            jg.writeNumberField("offset", traceEntry.getStartTick() - transactionStartTick);
            jg.writeFieldName("duration");
            long endTick = traceEntry.getEndTick();
            if (traceEntry.isCompleted() && Ticker.lessThanOrEqual(endTick, captureTick)) {
                jg.writeNumber(endTick - traceEntry.getStartTick());
            } else {
                jg.writeNumber(captureTick - traceEntry.getStartTick());
                jg.writeBooleanField("active", true);
            }
            jg.writeNumberField("nestingLevel", traceEntry.getNestingLevel());
            MessageSupplier messageSupplier = traceEntry.getMessageSupplier();
            if (messageSupplier != null) {
                jg.writeFieldName("message");
                writeMessage((ReadableMessage) messageSupplier.get());
            }
            ReadableErrorMessage errorMessage = traceEntry.getErrorMessage();
            if (errorMessage != null) {
                jg.writeFieldName("error");
                writeErrorMessage(errorMessage);
            }
            ImmutableList<StackTraceElement> stackTrace = traceEntry.getStackTrace();
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
            jg.writeStringField("display", exception.display());
            jg.writeFieldName("stackTrace");
            writeStackTrace(exception.stackTrace(), jg);
            jg.writeNumberField("framesInCommonWithCaused",
                    exception.framesInCommonWithCaused());
            ExceptionInfo cause = exception.cause();
            if (cause != null) {
                jg.writeFieldName("cause");
                writeException(cause, jg);
            }
            jg.writeEndObject();
        }
    }

    private static class CharArrayWriter extends java.io.CharArrayWriter {

        // provides access to protected char buffer
        public void arraycopy(int srcPos, char[] dest, int destPos, int length) {
            System.arraycopy(buf, srcPos, dest, destPos, length);
        }
    }
}
