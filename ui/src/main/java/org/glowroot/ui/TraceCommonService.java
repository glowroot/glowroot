/*
 * Copyright 2012-2016 the original author or authors.
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
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.util.Styles;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.TraceRepository.HeaderPlus;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.Proto;
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
    String getHeaderJson(String agentId, String traceId, boolean checkLiveTraces)
            throws Exception {
        if (checkLiveTraces) {
            // check active/pending traces first, and lastly stored traces to make sure that the
            // trace
            // is not missed if it is in transition between these states
            Trace.Header header = liveTraceRepository.getHeader(agentId, traceId);
            if (header != null) {
                return toJsonLiveHeader(header);
            }
        }
        HeaderPlus header = getStoredHeader(agentId, traceId, new RetryCountdown(checkLiveTraces));
        if (header == null) {
            return null;
        }
        return toJsonRepoHeader(header);
    }

    // TODO this comment is no longer valid?
    // overwritten entries will return {"overwritten":true}
    // expired (not found) trace will return {"expired":true}
    @Nullable
    String getEntriesJson(String agentId, String traceId, boolean checkLiveTraces)
            throws Exception {
        if (checkLiveTraces) {
            // check active/pending traces first, and lastly stored traces to make sure that the
            // trace is not missed if it is in transition between these states
            List<Trace.Entry> entries = liveTraceRepository.getEntries(agentId, traceId);
            if (!entries.isEmpty()) {
                return toJson(entries);
            }
        }
        return toJson(getStoredEntries(agentId, traceId, new RetryCountdown(checkLiveTraces)));
    }

    // overwritten profile will return {"overwritten":true}
    // expired (not found) trace will return {"expired":true}
    @Nullable
    String getMainThreadProfileJson(String agentId, String traceId, boolean checkLiveTraces)
            throws Exception {
        if (checkLiveTraces) {
            // check active/pending traces first, and lastly stored traces to make sure that the
            // trace is not missed if it is in transition between these states
            Profile profile = liveTraceRepository.getMainThreadProfile(agentId, traceId);
            if (profile != null) {
                return toJson(profile);
            }
        }
        return toJson(
                getStoredMainThreadProfile(agentId, traceId, new RetryCountdown(checkLiveTraces)));
    }

    // overwritten profile will return {"overwritten":true}
    // expired (not found) trace will return {"expired":true}
    @Nullable
    String getAuxThreadProfileJson(String agentId, String traceId, boolean checkLiveTraces)
            throws Exception {
        if (checkLiveTraces) {
            // check active/pending traces first, and lastly stored traces to make sure that the
            // trace is not missed if it is in transition between these states
            Profile profile = liveTraceRepository.getAuxThreadProfile(agentId, traceId);
            if (profile != null) {
                return toJson(profile);
            }
        }
        return toJson(
                getStoredAuxThreadProfile(agentId, traceId, new RetryCountdown(checkLiveTraces)));
    }

    @Nullable
    TraceExport getExport(String agentId, String traceId, boolean checkLiveTraces)
            throws Exception {
        if (checkLiveTraces) {
            // check active/pending traces first, and lastly stored traces to make sure that the
            // trace is not missed if it is in transition between these states
            Trace trace = liveTraceRepository.getFullTrace(agentId, traceId);
            if (trace != null) {
                Trace.Header header = trace.getHeader();
                return ImmutableTraceExport.builder()
                        .fileName(getFileName(header))
                        .headerJson(toJsonLiveHeader(header))
                        .entriesJson(toJson(trace.getEntryList()))
                        .mainThreadProfileJson(toJson(trace.getMainThreadProfile()))
                        .auxThreadProfileJson(toJson(trace.getAuxThreadProfile()))
                        .build();
            }
        }
        RetryCountdown retryCountdown = new RetryCountdown(checkLiveTraces);
        HeaderPlus header = getStoredHeader(agentId, traceId, retryCountdown);
        if (header == null) {
            return null;
        }
        ImmutableTraceExport.Builder builder = ImmutableTraceExport.builder()
                .fileName(getFileName(header.header()))
                .headerJson(toJsonRepoHeader(header));
        builder.entriesJson(toJson(getStoredEntries(agentId, traceId, retryCountdown)));
        builder.mainThreadProfileJson(
                toJson(getStoredMainThreadProfile(agentId, traceId, retryCountdown)));
        builder.auxThreadProfileJson(
                toJson(getStoredAuxThreadProfile(agentId, traceId, retryCountdown)));
        return builder.build();
    }

    private @Nullable HeaderPlus getStoredHeader(String agentId, String traceId,
            RetryCountdown retryCountdown) throws Exception {
        HeaderPlus headerPlus = traceRepository.readHeaderPlus(agentId, traceId);
        while (headerPlus == null && retryCountdown.remaining-- > 0) {
            // trace may be completed, but still in transit from agent to glowroot server
            Thread.sleep(500);
            headerPlus = traceRepository.readHeaderPlus(agentId, traceId);
        }
        return headerPlus;
    }

    private List<Trace.Entry> getStoredEntries(String agentId, String traceId,
            RetryCountdown retryCountdown) throws Exception {
        List<Trace.Entry> entries = traceRepository.readEntries(agentId, traceId);
        while (entries.isEmpty() && retryCountdown.remaining-- > 0) {
            // trace may be completed, but still in transit from agent to glowroot server
            Thread.sleep(500);
            entries = traceRepository.readEntries(agentId, traceId);
        }
        return entries;
    }

    private @Nullable Profile getStoredMainThreadProfile(String agentId, String traceId,
            RetryCountdown retryCountdown) throws Exception {
        Profile profile = traceRepository.readMainThreadProfile(agentId, traceId);
        while (profile == null && retryCountdown.remaining-- > 0) {
            // trace may be completed, but still in transit from agent to glowroot server
            Thread.sleep(500);
            profile = traceRepository.readMainThreadProfile(agentId, traceId);
        }
        return profile;
    }

    private @Nullable Profile getStoredAuxThreadProfile(String agentId, String traceId,
            RetryCountdown retryCountdown) throws Exception {
        Profile profile = traceRepository.readAuxThreadProfile(agentId, traceId);
        while (profile == null && retryCountdown.remaining-- > 0) {
            // trace may be completed, but still in transit from agent to glowroot server
            Thread.sleep(500);
            profile = traceRepository.readAuxThreadProfile(agentId, traceId);
        }
        return profile;
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

    private static @Nullable String toJson(@Nullable Profile profile) throws IOException {
        if (profile == null) {
            return null;
        }
        MutableProfile mutableProfile = new MutableProfile();
        mutableProfile.merge(profile);
        return mutableProfile.toJson();
    }

    private static String toJsonLiveHeader(Trace.Header header) throws IOException {
        boolean hasProfile = header.getMainThreadProfileSampleCount() > 0
                || header.getAuxThreadProfileSampleCount() > 0;
        return toJson(header, header.getPartial(),
                header.getEntryCount() > 0 ? Existence.YES : Existence.NO,
                hasProfile ? Existence.YES : Existence.NO);
    }

    private static String toJsonRepoHeader(HeaderPlus header) throws IOException {
        return toJson(header.header(), false, header.entriesExistence(), header.profileExistence());
    }

    private static String toJson(Trace.Header header, boolean active, Existence entriesExistence,
            Existence profileExistence) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
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
        if (header.hasMainThreadRootTimer()) {
            jg.writeFieldName("mainThreadRootTimer");
            writeTimer(header.getMainThreadRootTimer(), jg);
        }
        jg.writeArrayFieldStart("auxThreadRootTimers");
        for (Trace.Timer asyncRootTimer : header.getAuxThreadRootTimerList()) {
            writeTimer(asyncRootTimer, jg);
        }
        jg.writeEndArray();
        jg.writeArrayFieldStart("asyncRootTimers");
        for (Trace.Timer asyncRootTimer : header.getAsyncRootTimerList()) {
            writeTimer(asyncRootTimer, jg);
        }
        jg.writeEndArray();
        if (header.hasMainThreadStats()) {
            jg.writeFieldName("mainThreadStats");
            writeThreadStats(header.getMainThreadStats(), jg);
        }
        if (header.hasAuxThreadStats()) {
            jg.writeFieldName("auxThreadStats");
            writeThreadStats(header.getAuxThreadStats(), jg);
        }
        jg.writeNumberField("entryCount", header.getEntryCount());
        boolean entryLimitExceeded = header.getEntryLimitExceeded();
        if (entryLimitExceeded) {
            jg.writeBooleanField("entryLimitExceeded", entryLimitExceeded);
        }
        jg.writeNumberField("mainThreadProfileSampleCount",
                header.getMainThreadProfileSampleCount());
        boolean mainThreadProfileSampleLimitExceeded =
                header.getMainThreadProfileSampleLimitExceeded();
        if (mainThreadProfileSampleLimitExceeded) {
            jg.writeBooleanField("mainThreadProfileSampleLimitExceeded",
                    mainThreadProfileSampleLimitExceeded);
        }
        jg.writeNumberField("auxThreadProfileSampleCount", header.getAuxThreadProfileSampleCount());
        boolean auxThreadProfileSampleLimitExceeded =
                header.getAuxThreadProfileSampleLimitExceeded();
        if (auxThreadProfileSampleLimitExceeded) {
            jg.writeBooleanField("auxThreadProfileSampleLimitExceeded",
                    auxThreadProfileSampleLimitExceeded);
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
        if (entry.getActive()) {
            jg.writeBooleanField("active", true);
        }
        jg.writeStringField("message", entry.getMessage());
        List<Trace.DetailEntry> detailEntries = entry.getDetailEntryList();
        if (!detailEntries.isEmpty()) {
            jg.writeFieldName("detail");
            writeDetailEntries(detailEntries, jg);
        }
        List<Proto.StackTraceElement> locationStackTraceElements =
                entry.getLocationStackTraceElementList();
        if (!locationStackTraceElements.isEmpty()) {
            jg.writeArrayFieldStart("locationStackTraceElements");
            for (Proto.StackTraceElement stackTraceElement : locationStackTraceElements) {
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

    private static void writeDetailEntries(List<Trace.DetailEntry> detailEntries, JsonGenerator jg)
            throws IOException {
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
            case STRING:
                jg.writeString(value.getString());
                break;
            case DOUBLE:
                jg.writeNumber(value.getDouble());
                break;
            case LONG:
                jg.writeNumber(value.getLong());
                break;
            case BOOLEAN:
                jg.writeBoolean(value.getBoolean());
                break;
            default:
                throw new IllegalStateException("Unexpected detail value: " + value.getValCase());
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

    private static void writeThrowable(Proto.Throwable throwable, boolean hasEnclosing,
            JsonGenerator jg) throws IOException {
        jg.writeStartObject();
        jg.writeStringField("className", throwable.getClassName());
        jg.writeStringField("message", throwable.getMessage());
        jg.writeArrayFieldStart("stackTraceElements");
        for (Proto.StackTraceElement stackTraceElement : throwable.getStackTraceElementList()) {
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

    private static void writeThreadStats(Trace.ThreadStats threadStats, JsonGenerator jg)
            throws IOException {
        jg.writeStartObject();
        if (threadStats.hasTotalCpuNanos()) {
            jg.writeNumberField("totalCpuNanos", threadStats.getTotalCpuNanos().getValue());
        }
        if (threadStats.hasTotalBlockedNanos()) {
            jg.writeNumberField("totalBlockedNanos", threadStats.getTotalBlockedNanos().getValue());
        }
        if (threadStats.hasTotalWaitedNanos()) {
            jg.writeNumberField("totalWaitedNanos", threadStats.getTotalWaitedNanos().getValue());
        }
        if (threadStats.hasTotalAllocatedBytes()) {
            jg.writeNumberField("totalAllocatedBytes",
                    threadStats.getTotalAllocatedBytes().getValue());
        }
        jg.writeEndObject();
    }

    private static void writeStackTraceElement(Proto.StackTraceElement stackTraceElement,
            JsonGenerator jg) throws IOException {
        jg.writeString(new StackTraceElement(stackTraceElement.getClassName(),
                stackTraceElement.getMethodName(), stackTraceElement.getFileName(),
                stackTraceElement.getLineNumber()).toString());
    }

    private static String getFileName(Trace.Header header) {
        return "trace-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(header.getStartTime());
    }

    private static class RetryCountdown {

        private int remaining;

        public RetryCountdown(boolean checkLiveTraces) {
            remaining = checkLiveTraces ? 5 : 0;
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TraceExport {
        String fileName();
        String headerJson();
        @Nullable
        String entriesJson();
        @Nullable
        String mainThreadProfileJson();
        @Nullable
        String auxThreadProfileJson();
    }
}
