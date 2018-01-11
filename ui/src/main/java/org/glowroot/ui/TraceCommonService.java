/*
 * Copyright 2012-2017 the original author or authors.
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
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.google.common.io.CharStreams;
import org.immutables.value.Value;

import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveTraceRepository.Entries;
import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.repo.AgentRollupRepository;
import org.glowroot.common.repo.TraceRepository;
import org.glowroot.common.repo.TraceRepository.HeaderPlus;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

class TraceCommonService {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private final TraceRepository traceRepository;
    private final LiveTraceRepository liveTraceRepository;
    private final AgentRollupRepository agentRollupRepository;

    TraceCommonService(TraceRepository traceRepository, LiveTraceRepository liveTraceRepository,
            AgentRollupRepository agentRollupRepository) {
        this.traceRepository = traceRepository;
        this.liveTraceRepository = liveTraceRepository;
        this.agentRollupRepository = agentRollupRepository;
    }

    @Nullable
    String getHeaderJson(String agentId, String traceId, boolean checkLiveTraces) throws Exception {
        if (checkLiveTraces) {
            // check active/pending traces first, and lastly stored traces to make sure that the
            // trace is not missed if it is in transition between these states
            Trace.Header header;
            try {
                header = liveTraceRepository.getHeader(agentId, traceId);
            } catch (AgentNotConnectedException e) {
                header = null;
            } catch (TimeoutException e) {
                header = null;
            }
            if (header != null) {
                return toJsonLiveHeader(agentId, header);
            }
        }
        HeaderPlus header = getStoredHeader(agentId, traceId, new RetryCountdown(checkLiveTraces));
        if (header == null) {
            return null;
        }
        return toJsonRepoHeader(agentId, header);
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
            Entries entries;
            try {
                entries = liveTraceRepository.getEntries(agentId, traceId);
            } catch (AgentNotConnectedException e) {
                entries = null;
            } catch (TimeoutException e) {
                entries = null;
            }
            if (entries != null) {
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
            Profile profile;
            try {
                profile = liveTraceRepository.getMainThreadProfile(agentId, traceId);
            } catch (AgentNotConnectedException e) {
                profile = null;
            } catch (TimeoutException e) {
                profile = null;
            }
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
            Profile profile;
            try {
                profile = liveTraceRepository.getAuxThreadProfile(agentId, traceId);
            } catch (AgentNotConnectedException e) {
                profile = null;
            } catch (TimeoutException e) {
                profile = null;
            }
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
            Trace trace;
            try {
                trace = liveTraceRepository.getFullTrace(agentId, traceId);
            } catch (AgentNotConnectedException e) {
                trace = null;
            } catch (TimeoutException e) {
                trace = null;
            }
            if (trace != null) {
                Trace.Header header = trace.getHeader();
                return ImmutableTraceExport.builder()
                        .fileName(getFileName(header))
                        .headerJson(toJsonLiveHeader(agentId, header))
                        .entriesJson(entriesToJson(trace.getEntryList()))
                        // SharedQueryTexts are always returned from getFullTrace() above with
                        // fullTrace, so no need to resolve fullTraceSha1
                        .sharedQueryTextsJson(
                                sharedQueryTextsToJson(trace.getSharedQueryTextList()))
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
                .headerJson(toJsonRepoHeader(agentId, header));
        Entries entries = getStoredEntriesForExport(agentId, traceId, retryCountdown);
        if (entries != null) {
            builder.entriesJson(entriesToJson(entries.entries()));
            // SharedQueryTexts are always returned from getStoredEntries() above with fullTrace,
            // so no need to resolve fullTraceSha1
            builder.sharedQueryTextsJson(sharedQueryTextsToJson(entries.sharedQueryTexts()));
        }
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
            // trace may be completed, but still in transit from agent to the central collector
            Thread.sleep(500);
            headerPlus = traceRepository.readHeaderPlus(agentId, traceId);
        }
        return headerPlus;
    }

    private @Nullable Entries getStoredEntries(String agentId, String traceId,
            RetryCountdown retryCountdown) throws Exception {
        Entries entries = traceRepository.readEntries(agentId, traceId);
        while (entries == null && retryCountdown.remaining-- > 0) {
            // trace may be completed, but still in transit from agent to the central collector
            Thread.sleep(500);
            entries = traceRepository.readEntries(agentId, traceId);
        }
        return entries;
    }

    private @Nullable Entries getStoredEntriesForExport(String agentId, String traceId,
            RetryCountdown retryCountdown) throws Exception {
        Entries entries = traceRepository.readEntriesForExport(agentId, traceId);
        while (entries == null && retryCountdown.remaining-- > 0) {
            // trace may be completed, but still in transit from agent to the central collector
            Thread.sleep(500);
            entries = traceRepository.readEntriesForExport(agentId, traceId);
        }
        return entries;
    }

    private @Nullable Profile getStoredMainThreadProfile(String agentId, String traceId,
            RetryCountdown retryCountdown) throws Exception {
        Profile profile = traceRepository.readMainThreadProfile(agentId, traceId);
        while (profile == null && retryCountdown.remaining-- > 0) {
            // trace may be completed, but still in transit from agent to the central collector
            Thread.sleep(500);
            profile = traceRepository.readMainThreadProfile(agentId, traceId);
        }
        return profile;
    }

    private @Nullable Profile getStoredAuxThreadProfile(String agentId, String traceId,
            RetryCountdown retryCountdown) throws Exception {
        Profile profile = traceRepository.readAuxThreadProfile(agentId, traceId);
        while (profile == null && retryCountdown.remaining-- > 0) {
            // trace may be completed, but still in transit from agent to the central collector
            Thread.sleep(500);
            profile = traceRepository.readAuxThreadProfile(agentId, traceId);
        }
        return profile;
    }

    private static @Nullable String toJson(@Nullable Entries entries) throws IOException {
        if (entries == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeFieldName("entries");
            writeEntries(jg, entries.entries());
            jg.writeFieldName("sharedQueryTexts");
            writeSharedQueryTexts(jg, entries.sharedQueryTexts());
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @VisibleForTesting
    static @Nullable String entriesToJson(List<Trace.Entry> entries) throws IOException {
        if (entries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        try {
            writeEntries(jg, entries);
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    private static @Nullable String sharedQueryTextsToJson(
            List<Trace.SharedQueryText> sharedQueryTexts) throws IOException {
        if (sharedQueryTexts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        try {
            writeSharedQueryTexts(jg, sharedQueryTexts);
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    private static void writeEntries(JsonGenerator jg, List<Trace.Entry> entries)
            throws IOException {
        jg.writeStartArray();
        PeekingIterator<Trace.Entry> i = Iterators.peekingIterator(entries.iterator());
        while (i.hasNext()) {
            Trace.Entry entry = i.next();
            int depth = entry.getDepth();
            jg.writeStartObject();
            writeJson(entry, jg);
            int nextDepth = i.hasNext() ? i.peek().getDepth() : 0;
            if (nextDepth > depth) {
                jg.writeArrayFieldStart("childEntries");
            } else if (nextDepth < depth) {
                jg.writeEndObject();
                for (int j = depth; j > nextDepth; j--) {
                    jg.writeEndArray();
                    jg.writeEndObject();
                }
            } else {
                jg.writeEndObject();
            }
        }
        jg.writeEndArray();
    }

    private static void writeSharedQueryTexts(JsonGenerator jg,
            List<Trace.SharedQueryText> sharedQueryTexts) throws IOException {
        jg.writeStartArray();
        for (Trace.SharedQueryText sharedQueryText : sharedQueryTexts) {
            jg.writeStartObject();
            String fullText = sharedQueryText.getFullText();
            if (fullText.isEmpty()) {
                // truncatedText, truncatedEndText and fullTextSha1 are all provided in this case
                jg.writeStringField("truncatedText", sharedQueryText.getTruncatedText());
                jg.writeStringField("truncatedEndText", sharedQueryText.getTruncatedEndText());
                jg.writeStringField("fullTextSha1", sharedQueryText.getFullTextSha1());
            } else {
                jg.writeStringField("fullText", fullText);
            }
            jg.writeEndObject();
        }
        jg.writeEndArray();
    }

    private static @Nullable String toJson(@Nullable Profile profile) throws IOException {
        if (profile == null) {
            return null;
        }
        MutableProfile mutableProfile = new MutableProfile();
        mutableProfile.merge(profile);
        return mutableProfile.toJson();
    }

    private String toJsonLiveHeader(String agentId, Trace.Header header) throws Exception {
        boolean hasProfile = header.getMainThreadProfileSampleCount() > 0
                || header.getAuxThreadProfileSampleCount() > 0;
        return toJson(agentId, header, header.getPartial(),
                header.getEntryCount() > 0 ? Existence.YES : Existence.NO,
                hasProfile ? Existence.YES : Existence.NO);
    }

    private String toJsonRepoHeader(String agentId, HeaderPlus header) throws Exception {
        return toJson(agentId, header.header(), false, header.entriesExistence(),
                header.profileExistence());
    }

    private String toJson(String agentId, Trace.Header header, boolean active,
            Existence entriesExistence, Existence profileExistence) throws Exception {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            if (!agentId.isEmpty()) {
                jg.writeStringField("agent", agentRollupRepository.readAgentRollupDisplay(agentId));
            }
            if (active) {
                jg.writeBooleanField("active", active);
            }
            boolean partial = header.getPartial();
            if (partial) {
                jg.writeBooleanField("partial", partial);
            }
            boolean async = header.getAsync();
            if (async) {
                jg.writeBooleanField("async", async);
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
            for (Trace.Timer rootTimer : header.getAuxThreadRootTimerList()) {
                writeTimer(rootTimer, jg);
            }
            jg.writeEndArray();
            jg.writeArrayFieldStart("asyncTimers");
            for (Trace.Timer asyncTimer : header.getAsyncTimerList()) {
                writeTimer(asyncTimer, jg);
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
            jg.writeNumberField("auxThreadProfileSampleCount",
                    header.getAuxThreadProfileSampleCount());
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
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    private static void writeJson(Trace.Entry entry, JsonGenerator jg) throws IOException {
        jg.writeNumberField("startOffsetNanos", entry.getStartOffsetNanos());
        jg.writeNumberField("durationNanos", entry.getDurationNanos());
        if (entry.getActive()) {
            jg.writeBooleanField("active", true);
        }
        if (entry.hasQueryEntryMessage()) {
            jg.writeObjectFieldStart("queryMessage");
            Trace.QueryEntryMessage queryMessage = entry.getQueryEntryMessage();
            jg.writeNumberField("sharedQueryTextIndex", queryMessage.getSharedQueryTextIndex());
            jg.writeStringField("prefix", queryMessage.getPrefix());
            jg.writeStringField("suffix", queryMessage.getSuffix());
            jg.writeEndObject();
        } else {
            jg.writeStringField("message", entry.getMessage());
        }
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
        String sharedQueryTextsJson();
        @Nullable
        String mainThreadProfileJson();
        @Nullable
        String auxThreadProfileJson();
    }
}
