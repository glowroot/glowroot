/*
 * Copyright 2015-2023 the original author or authors.
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
package org.glowroot.common2.repo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.common.live.LiveTraceRepository.Entries;
import org.glowroot.common.live.LiveTraceRepository.EntriesAndQueries;
import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.live.LiveTraceRepository.Queries;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.model.Result;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public interface TraceRepository {

    long readSlowCount(String agentRollupId, TraceQuery query) throws Exception;

    CompletableFuture<Result<TracePoint>> readSlowPoints(String agentRollupId, TraceQuery query,
                                                        TracePointFilter filter, int limit) throws Exception;

    long readErrorCount(String agentRollupId, TraceQuery query) throws Exception;

    CompletableFuture<Result<TracePoint>> readErrorPoints(String agentRollupId, TraceQuery query,
            TracePointFilter filter, int limit) throws Exception;

    ErrorMessageResult readErrorMessages(String agentRollupId, TraceQuery query,
            ErrorMessageFilter filter, long resolutionMillis, int limit) throws Exception;

    long readErrorMessageCount(String agentRollupId, TraceQuery query, String errorMessageFilter)
            throws Exception;

    // null return value means trace not found
    @Nullable
    HeaderPlus readHeaderPlus(String agentId, String traceId) throws Exception;

    // null return value means trace not found or was found but had no entries
    //
    // SharedQueryTexts are returned with either fullTrace or
    // truncatedText/truncatedEndText/fullTraceSha1
    @Nullable
    Entries readEntries(String agentId, String traceId) throws Exception;

    // null return value means trace not found or was found but had no queries
    //
    // SharedQueryTexts are returned with either fullTrace or
    // truncatedText/truncatedEndText/fullTraceSha1
    @Nullable
    Queries readQueries(String agentId, String traceId) throws Exception;

    // null return value means trace not found or was found but had no entries
    //
    // since this is only used by export, SharedQueryTexts are always returned with fullTrace
    // (never with truncatedText/truncatedEndText/fullTraceSha1)
    @Nullable
    EntriesAndQueries readEntriesAndQueriesForExport(String agentId, String traceId)
            throws Exception;

    // null return value means trace not found or was found but had no main thread profile
    @Nullable
    Profile readMainThreadProfile(String agentId, String traceId) throws Exception;

    // null return value means trace not found or was found but had no aux thread profile
    @Nullable
    Profile readAuxThreadProfile(String agentId, String traceId) throws Exception;

    @Value.Immutable
    interface TraceQuery {
        String transactionType();
        @Nullable
        String transactionName();
        long from();
        long to();
    }

    @Value.Immutable
    interface ErrorMessageFilter {
        List<String> includes();
        List<String> excludes();
    }

    @Value.Immutable
    interface ErrorMessageResult {
        List<ErrorMessagePoint> points();
        Result<ErrorMessageCount> counts();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface ErrorMessagePoint {
        long captureTime();
        long errorCount();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface ErrorMessageCount {
        String message();
        long count();
    }

    @Value.Immutable
    interface HeaderPlus {
        Trace.Header header();
        Existence entriesExistence();
        Existence queriesExistence();
        // EXPIRED if either main thread or auxiliary thread profile exist and are expired
        // YES if either main thread or auxiliary thread profile exists
        // NO if both main thread or auxiliary thread profile do not exists
        Existence profileExistence();
    }
}
