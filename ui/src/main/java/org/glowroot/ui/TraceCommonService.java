/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.ui;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.io.CharStreams;
import org.immutables.value.Value;

import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.model.MutableProfileTree;
import org.glowroot.common.util.Styles;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.TraceRepository.HeaderPlus;
import org.glowroot.wire.api.model.ProfileTreeOuterClass.ProfileTree;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

class TraceCommonService {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private final TraceRepository traceRepository;
    private final LiveTraceRepository liveTraceRepository;

    TraceCommonService(TraceRepository traceRepository, LiveTraceRepository liveTraceRepository) {
        this.traceRepository = traceRepository;
        this.liveTraceRepository = liveTraceRepository;
    }

    @Nullable
    String getHeaderJson(long serverId, String traceId) throws Exception {
        // check active/pending traces first, and lastly stored traces to make sure that the trace
        // is not missed if it is in transition between these states
        Trace.Header header = liveTraceRepository.getHeader(serverId, traceId);
        if (header != null) {
            return toJsonLiveHeader(header);
        }
        HeaderPlus headerPlus = traceRepository.readHeader(serverId, traceId);
        if (headerPlus != null) {
            return toJsonRepoHeader(headerPlus);
        }
        return null;
    }

    // TODO this comment is no longer valid?
    // overwritten entries will return {"overwritten":true}
    // expired (not found) trace will return {"expired":true}
    @Nullable
    String getEntriesJson(long serverId, String traceId) throws Exception {
        // check active/pending traces first, and lastly stored traces to make sure that the trace
        // is not missed if it is in transition between these states
        List<Trace.Entry> entries = liveTraceRepository.getEntries(serverId, traceId);
        if (entries.isEmpty()) {
            entries = traceRepository.readEntries(serverId, traceId);
        }
        return toJson(entries);
    }

    // overwritten profile will return {"overwritten":true}
    // expired (not found) trace will return {"expired":true}
    @Nullable
    String getProfileTreeJson(long serverId, String traceId) throws Exception {
        // check active/pending traces first, and lastly stored traces to make sure that the trace
        // is not missed if it is in transition between these states
        ProfileTree profileTree = liveTraceRepository.getProfileTree(serverId, traceId);
        if (profileTree == null) {
            profileTree = traceRepository.readProfileTree(serverId, traceId);
        }
        return toJson(profileTree);
    }

    @Nullable
    TraceExport getExport(long serverId, String traceId) throws Exception {
        // check active/pending traces first, and lastly stored traces to make sure that the trace
        // is not missed if it is in transition between these states
        Trace trace = liveTraceRepository.getFullTrace(serverId, traceId);
        if (trace != null) {
            Trace.Header header = trace.getHeader();
            return ImmutableTraceExport.builder()
                    .fileName(getFilename(header))
                    .headerJson(toJsonLiveHeader(header))
                    .entriesJson(toJson(trace.getEntryList()))
                    .profileTreeJson(toJson(trace.getProfileTree()))
                    .build();
        }

        HeaderPlus header = traceRepository.readHeader(serverId, traceId);
        if (header == null) {
            return null;
        }
        ImmutableTraceExport.Builder builder = ImmutableTraceExport.builder()
                .fileName(getFilename(header.header()))
                .headerJson(toJsonRepoHeader(header));
        if (header.entriesExistence() == Existence.YES) {
            builder.entriesJson(toJson(traceRepository.readEntries(serverId, traceId)));
        }
        if (header.profileExistence() == Existence.YES) {
            builder.profileTreeJson(toJson(traceRepository.readProfileTree(serverId, traceId)));
        }
        return builder.build();
    }

    private static @Nullable String toJson(List<Trace.Entry> entries) throws IOException {
        if (entries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartArray();
        for (Trace.Entry entry : entries) {
            writeJson(entry, jg);
        }
        jg.writeEndArray();
        jg.close();
        return sb.toString();
    }

    private static @Nullable String toJson(@Nullable ProfileTree profileTree) throws IOException {
        if (profileTree == null) {
            return null;
        }
        MutableProfileTree mutableProfileTree = new MutableProfileTree();
        mutableProfileTree.merge(profileTree);
        return mutableProfileTree.toJson();
    }

    private static String toJsonLiveHeader(Trace.Header header) throws IOException {
        return toJson(header, header.getPartial(),
                header.getEntryCount() > 0 ? Existence.YES : Existence.NO,
                header.getProfileSampleCount() > 0 ? Existence.YES : Existence.NO);
    }

    private static String toJsonRepoHeader(HeaderPlus header) throws IOException {
        return toJson(header.header(), false, header.entriesExistence(), header.profileExistence());
    }

    private static String toJson(Trace.Header header, boolean active, Existence entriesExistence,
            Existence profileExistence) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("id", header.getId());
        if (active) {
            jg.writeBooleanField("active", active);
        }
        boolean partial = header.getPartial();
        if (partial) {
            jg.writeBooleanField("partial", partial);
        }
        boolean slow = header.getSlow();
        if (slow) {
            jg.writeBooleanField("slow", slow);
        }
        jg.writeNumberField("startTime", header.getStartTime());
        jg.writeNumberField("captureTime", header.getCaptureTime());
        jg.writeNumberField("durationNanos", header.getDurationNanos());
        jg.writeStringField("transactionType", header.getTransactionType());
        jg.writeStringField("transactionName", header.getTransactionName());
        jg.writeStringField("headline", header.getHeadline());
        jg.writeStringField("user", header.getUser());
        List<Trace.Attribute> attributes = header.getAttributeList();
        if (!attributes.isEmpty()) {
            jg.writeObjectFieldStart("attributes");
            for (Trace.Attribute attribute : attributes) {
                jg.writeArrayFieldStart(attribute.getName());
                for (String value : attribute.getValueList()) {
                    jg.writeString(value);
                }
                jg.writeEndArray();
            }
            jg.writeEndObject();
        }

        List<Trace.DetailEntry> detailEntries = header.getDetailEntryList();
        if (!detailEntries.isEmpty()) {
            jg.writeFieldName("detail");
            writeDetailEntries(detailEntries, jg);
        }

        if (header.hasError()) {
            jg.writeFieldName("error");
            writeError(header.getError(), jg);
        }
        jg.writeFieldName("rootTimer");
        writeTimer(header.getRootTimer(), jg);
        long threadCpuNanos = header.getThreadCpuNanos();
        if (threadCpuNanos != -1) {
            jg.writeNumberField("threadCpuNanos", threadCpuNanos);
        }
        long threadBlockedNanos = header.getThreadBlockedNanos();
        if (threadBlockedNanos != -1) {
            jg.writeNumberField("threadBlockedNanos", threadBlockedNanos);
        }
        long threadWaitedNanos = header.getThreadWaitedNanos();
        if (threadWaitedNanos != -1) {
            jg.writeNumberField("threadWaitedNanos", threadWaitedNanos);
        }
        long threadAllocatedBytes = header.getThreadAllocatedBytes();
        if (threadAllocatedBytes != -1) {
            jg.writeNumberField("threadAllocatedBytes", threadAllocatedBytes);
        }
        List<Trace.GarbageCollectionActivity> gcActivities = header.getGcActivityList();
        if (!gcActivities.isEmpty()) {
            jg.writeArrayFieldStart("gcActivities");
            for (Trace.GarbageCollectionActivity gcActivity : gcActivities) {
                jg.writeStartObject();
                jg.writeStringField("collectorName", gcActivity.getCollectorName());
                jg.writeNumberField("totalMillis", gcActivity.getTotalMillis());
                jg.writeNumberField("count", gcActivity.getCount());
                jg.writeEndObject();
            }
            jg.writeEndArray();
        }
        jg.writeNumberField("entryCount", header.getEntryCount());
        boolean entryLimitExceeded = header.getEntryLimitExceeded();
        if (entryLimitExceeded) {
            jg.writeBooleanField("entryLimitExceeded", entryLimitExceeded);
        }
        jg.writeNumberField("profileSampleCount", header.getProfileSampleCount());
        boolean profileSampleLimitExceeded = header.getProfileSampleLimitExceeded();
        if (profileSampleLimitExceeded) {
            jg.writeBooleanField("profileSampleLimitExceeded", profileSampleLimitExceeded);
        }
        jg.writeStringField("entriesExistence",
                entriesExistence.name().toLowerCase(Locale.ENGLISH));
        jg.writeStringField("profileExistence",
                profileExistence.name().toLowerCase(Locale.ENGLISH));
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private static void writeJson(Trace.Entry entry, JsonGenerator jg) throws IOException {
        jg.writeStartObject();
        jg.writeNumberField("startOffsetNanos", entry.getStartOffsetNanos());
        jg.writeNumberField("durationNanos", entry.getDurationNanos());
        boolean active = entry.getActive();
        if (active) {
            jg.writeBooleanField("active", true);
        }
        jg.writeStringField("message", entry.getMessage());
        List<Trace.DetailEntry> detailEntries = entry.getDetailEntryList();
        if (!detailEntries.isEmpty()) {
            jg.writeFieldName("detail");
            writeDetailEntries(detailEntries, jg);
        }
        List<Trace.StackTraceElement> locationStackTraceElements =
                entry.getLocationStackTraceElementList();
        if (!locationStackTraceElements.isEmpty()) {
            jg.writeArrayFieldStart("locationStackTraceElements");
            for (Trace.StackTraceElement stackTraceElement : locationStackTraceElements) {
                writeStackTraceElement(stackTraceElement, jg);
            }
            jg.writeEndArray();
        }
        if (entry.hasError()) {
            jg.writeFieldName("error");
            writeError(entry.getError(), jg);
        }
        List<Trace.Entry> childEntries = entry.getChildEntryList();
        if (!childEntries.isEmpty()) {
            jg.writeArrayFieldStart("childEntries");
            for (Trace.Entry childEntry : childEntries) {
                writeJson(childEntry, jg);
            }
            jg.writeEndArray();
        }
        jg.writeEndObject();
    }

    private static void writeDetailEntries(List<Trace.DetailEntry> detailEntries,
            JsonGenerator jg) throws IOException {
        jg.writeStartObject();
        for (Trace.DetailEntry detailEntry : detailEntries) {
            jg.writeFieldName(detailEntry.getName());
            List<Trace.DetailEntry> childEntries = detailEntry.getChildEntryList();
            List<Trace.DetailValue> values = detailEntry.getValueList();
            if (!childEntries.isEmpty()) {
                writeDetailEntries(childEntries, jg);
            } else if (values.size() == 1) {
                writeValue(values.get(0), jg);
            } else if (values.size() > 1) {
                jg.writeStartArray();
                for (Trace.DetailValue value : values) {
                    writeValue(value, jg);
                }
                jg.writeEndArray();
            } else {
                jg.writeNull();
            }
        }
        jg.writeEndObject();
    }

    private static void writeValue(Trace.DetailValue value, JsonGenerator jg) throws IOException {
        switch (value.getValCase()) {
            case SVAL:
                jg.writeString(value.getSval());
                break;
            case DVAL:
                jg.writeNumber(value.getDval());
                break;
            case LVAL:
                jg.writeNumber(value.getLval());
                break;
            case BVAL:
                jg.writeBoolean(value.getBval());
                break;
            default:
                throw new IllegalStateException(
                        "Unexpected detail value: " + value.getValCase());
        }
    }

    private static void writeError(Trace.Error error, JsonGenerator jg) throws IOException {
        jg.writeStartObject();
        jg.writeStringField("message", error.getMessage());
        if (error.hasException()) {
            jg.writeFieldName("exception");
            writeThrowable(error.getException(), false, jg);
        }
        jg.writeEndObject();
    }

    private static void writeThrowable(Trace.Throwable throwable, boolean hasEnclosing,
            JsonGenerator jg) throws IOException {
        jg.writeStartObject();
        jg.writeStringField("display", throwable.getDisplay());
        jg.writeArrayFieldStart("stackTraceElements");
        for (Trace.StackTraceElement stackTraceElement : throwable.getElementList()) {
            writeStackTraceElement(stackTraceElement, jg);
        }
        jg.writeEndArray();
        if (hasEnclosing) {
            jg.writeNumberField("framesInCommonWithEnclosing",
                    throwable.getFramesInCommonWithEnclosing());
        }
        if (throwable.hasCause()) {
            jg.writeFieldName("cause");
            writeThrowable(throwable.getCause(), true, jg);
        }
        jg.writeEndObject();
    }

    private static void writeTimer(Trace.Timer timer, JsonGenerator jg) throws IOException {
        jg.writeStartObject();
        jg.writeStringField("name", timer.getName());
        boolean extended = timer.getExtended();
        if (extended) {
            jg.writeBooleanField("extended", extended);
        }
        jg.writeNumberField("totalNanos", timer.getTotalNanos());
        jg.writeNumberField("count", timer.getCount());
        boolean active = timer.getActive();
        if (active) {
            jg.writeBooleanField("active", active);
        }
        List<Trace.Timer> childTimers = timer.getChildTimerList();
        if (!childTimers.isEmpty()) {
            jg.writeArrayFieldStart("childTimers");
            for (Trace.Timer childTimer : childTimers) {
                writeTimer(childTimer, jg);
            }
            jg.writeEndArray();
        }
        jg.writeEndObject();
    }

    private static void writeStackTraceElement(Trace.StackTraceElement stackTraceElement,
            JsonGenerator jg) throws IOException {
        jg.writeString(new StackTraceElement(stackTraceElement.getClassName(),
                stackTraceElement.getMethodName(), stackTraceElement.getFileName(),
                stackTraceElement.getLineNumber()).toString());
    }

    private static String getFilename(Trace.Header header) {
        return "trace-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(header.getStartTime());
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TraceExport {
        String fileName();
        String headerJson();
        @Nullable
        String entriesJson();
        @Nullable
        String profileTreeJson();
    }
}
