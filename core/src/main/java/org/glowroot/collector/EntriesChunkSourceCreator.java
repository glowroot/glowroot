/*
 * Copyright 2014-2015 the original author or authors.
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
import java.io.Writer;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableList;

import org.glowroot.api.MessageSupplier;
import org.glowroot.api.internal.ReadableErrorMessage;
import org.glowroot.api.internal.ReadableMessage;
import org.glowroot.api.internal.ThrowableInfo;
import org.glowroot.common.ChunkSource;
import org.glowroot.common.ChunkSource.ChunkCopier;
import org.glowroot.common.Tickers;
import org.glowroot.transaction.model.Profile;
import org.glowroot.transaction.model.StackTraceElementPlus;
import org.glowroot.transaction.model.TraceEntryImpl;

public class EntriesChunkSourceCreator {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private EntriesChunkSourceCreator() {}

    public static @Nullable ChunkSource createEntriesChunkSource(Iterable<TraceEntryImpl> entries,
            long transactionStartTick, long captureTick) {
        Iterator<TraceEntryImpl> entriesIterator = entries.iterator();
        if (!entriesIterator.hasNext()) {
            return null;
        }
        return new EntriesChunkSource(entriesIterator, transactionStartTick, captureTick);
    }

    static void writeThrowable(ThrowableInfo exception, JsonGenerator jg) throws IOException {
        jg.writeStartObject();
        jg.writeStringField("display", exception.display());
        jg.writeFieldName("stackTrace");
        writeStackTrace(exception.stackTrace(), jg);
        jg.writeNumberField("framesInCommonWithCaused", exception.framesInCommonWithCaused());
        ThrowableInfo cause = exception.cause();
        if (cause != null) {
            jg.writeFieldName("cause");
            writeThrowable(cause, jg);
        }
        jg.writeEndObject();
    }

    private static void writeStackTrace(List<StackTraceElement> stackTrace, JsonGenerator jw)
            throws IOException {
        jw.writeStartArray();
        List<StackTraceElementPlus> elements = Profile.stripSyntheticTimerMethods(stackTrace);
        for (StackTraceElementPlus element : elements) {
            jw.writeString(element.stackTraceElement().toString());
        }
        jw.writeEndArray();
    }

    private static class EntriesChunkSource extends ChunkSource {

        private final Iterator<TraceEntryImpl> entries;
        private final long transactionStartTick;
        private final long captureTick;

        private EntriesChunkSource(Iterator<TraceEntryImpl> entries,
                long transactionStartTick, long captureTick) {
            this.entries = entries;
            this.transactionStartTick = transactionStartTick;
            this.captureTick = captureTick;
        }

        @Override
        public ChunkCopier getCopier(Writer writer) throws IOException {
            return new EntriesChunkCopier(entries, writer, transactionStartTick, captureTick);
        }
    }

    private static class EntriesChunkCopier implements ChunkCopier {

        private final Iterator<TraceEntryImpl> entries;
        private final long transactionStartTick;
        private final long captureTick;
        private final JsonGenerator jg;

        private boolean closed;

        private EntriesChunkCopier(Iterator<TraceEntryImpl> entries, Writer writer,
                long transactionStartTick, long captureTick) throws IOException {
            this.entries = entries;
            this.transactionStartTick = transactionStartTick;
            this.captureTick = captureTick;
            jg = jsonFactory.createGenerator(writer);
            jg.writeStartArray();
        }

        // timings for transactions that are still active are normalized to the capture tick in
        // order to *attempt* to present a picture of the transaction at that exact tick
        // (without using synchronization to block updates to the transaction while it is being
        // read)
        @Override
        public boolean copyNext() throws IOException {
            if (closed) {
                return false;
            }
            TraceEntryImpl traceEntry = entries.next();
            if (!Tickers.lessThanOrEqual(traceEntry.getStartTick(), captureTick)) {
                // this entry started after the capture tick
                jg.writeEndArray();
                jg.flush();
                closed = true;
                return true;
            }
            if (traceEntry.isLimitExceededMarker()) {
                writeLimitExceededEntry();
            } else if (traceEntry.isLimitExtendedMarker()) {
                writeLimitExtendedEntry();
            } else {
                writeNormalEntry(traceEntry);
            }
            if (!entries.hasNext()) {
                jg.writeEndArray();
                closed = true;
            }
            jg.flush();
            return true;
        }

        private void writeLimitExceededEntry() throws IOException {
            jg.writeStartObject();
            jg.writeBooleanField("limitExceededMarker", true);
            jg.writeEndObject();
        }

        private void writeLimitExtendedEntry() throws IOException {
            jg.writeStartObject();
            jg.writeBooleanField("limitExtendedMarker", true);
            jg.writeEndObject();
        }

        private void writeNormalEntry(TraceEntryImpl traceEntry) throws IOException {
            jg.writeStartObject();
            jg.writeNumberField("offset", traceEntry.getStartTick() - transactionStartTick);
            jg.writeFieldName("duration");
            long endTick = traceEntry.getEndTick();
            if (traceEntry.isCompleted() && Tickers.lessThanOrEqual(endTick, captureTick)) {
                // duration is calculated relative to revised start tick
                jg.writeNumber(endTick - traceEntry.getRevisedStartTick());
            } else {
                // duration is calculated relative to revised start tick
                jg.writeNumber(captureTick - traceEntry.getRevisedStartTick());
                jg.writeBooleanField("active", true);
            }
            jg.writeNumberField("nestingLevel", traceEntry.getNestingLevel());
            MessageSupplier messageSupplier = traceEntry.getMessageSupplier();
            if (messageSupplier != null) {
                jg.writeFieldName("message");
                writeMessage((ReadableMessage) messageSupplier.get(),
                        getRowCountSuffix(traceEntry));
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

        private String getRowCountSuffix(TraceEntryImpl traceEntry) {
            if (!traceEntry.isQueryNavigationAttempted()) {
                return "";
            }
            long rowCount = traceEntry.getRowCount();
            if (rowCount == 1) {
                return " => 1 row";
            } else {
                return " => " + rowCount + " rows";
            }
        }

        private void writeMessage(ReadableMessage message, String messageSuffix)
                throws IOException {
            jg.writeStartObject();
            jg.writeStringField("text", message.getText() + messageSuffix);
            Map<String, ? extends /*@Nullable*/Object> detail = message.getDetail();
            if (!detail.isEmpty()) {
                jg.writeFieldName("detail");
                new DetailMapWriter(jg).write(detail);
            }
            jg.writeEndObject();
        }

        private void writeErrorMessage(ReadableErrorMessage errorMessage) throws IOException {
            jg.writeStartObject();
            jg.writeStringField("message", errorMessage.getMessage());
            ThrowableInfo throwable = errorMessage.getThrowable();
            if (throwable != null) {
                jg.writeFieldName("throwable");
                writeThrowable(throwable, jg);
            }
            jg.writeEndObject();
        }
    }
}
