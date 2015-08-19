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
package org.glowroot.common.repo.helper;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.glowroot.collector.spi.ThrowableInfo;
import org.glowroot.collector.spi.TraceEntry;
import org.glowroot.common.util.ChunkSource;
import org.glowroot.common.util.ChunkSource.ChunkCopier;
import org.glowroot.common.util.ObjectMappers;

public class EntriesChunkSourceCreator {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private EntriesChunkSourceCreator() {}

    public static @Nullable ChunkSource createEntriesChunkSource(
            Collection<? extends TraceEntry> entries) {
        Iterator<? extends TraceEntry> entriesIterator = entries.iterator();
        if (!entriesIterator.hasNext()) {
            return null;
        }
        return new EntriesChunkSource(entriesIterator);
    }

    public static void writeThrowable(ThrowableInfo throwable, JsonGenerator jg)
            throws IOException {
        mapper.writeValue(jg, throwable);
    }

    private static class EntriesChunkSource extends ChunkSource {

        private final Iterator<? extends TraceEntry> entries;

        private EntriesChunkSource(Iterator<? extends TraceEntry> entries) {
            this.entries = entries;
        }

        @Override
        public ChunkCopier getCopier(Writer writer) throws IOException {
            return new EntriesChunkCopier(entries, writer);
        }
    }

    private static class EntriesChunkCopier implements ChunkCopier {

        private final Iterator<? extends TraceEntry> entries;
        private final JsonGenerator jg;

        private boolean closed;

        private EntriesChunkCopier(Iterator<? extends TraceEntry> entries, Writer writer)
                throws IOException {
            this.entries = entries;
            jg = mapper.getFactory().createGenerator(writer);
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
            writeEntry(entries.next());
            if (!entries.hasNext()) {
                jg.writeEndArray();
                jg.flush();
                closed = true;
            }
            return true;
        }

        private void writeEntry(TraceEntry traceEntry) throws IOException {
            jg.writeStartObject();
            jg.writeNumberField("offset", traceEntry.offset());
            jg.writeNumberField("duration", traceEntry.duration());
            if (traceEntry.active()) {
                jg.writeBooleanField("active", true);
            }
            jg.writeNumberField("nestingLevel", traceEntry.nestingLevel());
            String messageText = traceEntry.messageText();
            if (messageText != null) {
                jg.writeStringField("messageText", messageText);
                Map<String, ? extends /*@Nullable*/Object> detail = traceEntry.messageDetail();
                if (!detail.isEmpty()) {
                    jg.writeFieldName("messageDetail");
                    new DetailMapWriter(jg).write(detail);
                }
            }
            String errorMessage = traceEntry.errorMessage();
            if (errorMessage != null) {
                jg.writeStringField("errorMessage", errorMessage);
                ThrowableInfo throwable = traceEntry.errorThrowable();
                if (throwable != null) {
                    jg.writeFieldName("errorThrowable");
                    writeThrowable(throwable, jg);
                }
            }
            Collection<StackTraceElement> stackTrace = traceEntry.stackTrace();
            if (stackTrace != null) {
                jg.writeObjectField("stackTrace", stackTrace);
            }
            jg.writeEndObject();
        }
    }
}
