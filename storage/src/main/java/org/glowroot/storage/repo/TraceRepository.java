/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.storage.repo;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public interface TraceRepository {

    void collect(String agentId, Trace trace) throws Exception;

    List<String> readTraceAttributeNames(String agentRollup, String transactionType)
            throws Exception;

    Result<TracePoint> readSlowPoints(TraceQuery query, TracePointFilter filter, int limit)
            throws Exception;

    Result<TracePoint> readErrorPoints(TraceQuery query, TracePointFilter filter, int limit)
            throws Exception;

    long readSlowCount(TraceQuery query) throws Exception;

    long readErrorCount(TraceQuery query) throws Exception;

    ErrorMessageResult readErrorMessages(TraceQuery query, ErrorMessageFilter filter,
            long resolutionMillis, int limit) throws Exception;

    @Nullable
    HeaderPlus readHeaderPlus(String agentId, String traceId) throws Exception;

    List<Trace.Entry> readEntries(String agentId, String traceId) throws Exception;

    @Nullable
    Profile readMainThreadProfile(String agentId, String traceId) throws Exception;

    @Nullable
    Profile readAuxThreadProfile(String agentId, String traceId) throws Exception;

    void deleteAll(String agentRollup) throws Exception;

    @Value.Immutable
    public interface TraceQuery {
        String agentRollup();
        String transactionType();
        @Nullable
        String transactionName();
        long from();
        long to();
    }

    @Value.Immutable
    public interface ErrorMessageFilter {
        ImmutableList<String> includes();
        ImmutableList<String> excludes();
    }

    @Value.Immutable
    public interface ErrorMessageResult {
        List<ErrorMessagePoint> points();
        Result<ErrorMessageCount> counts();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface ErrorMessagePoint {
        long captureTime();
        long errorCount();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface ErrorMessageCount {
        String message();
        long count();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface HeaderPlus {
        Trace.Header header();
        Existence entriesExistence();
        // EXPIRED if either main thread or auxiliary thread profile exist and are expired
        // YES if either main thread or auxiliary thread profile exists
        // NO if both main thread or auxiliary thread profile do not exists
        Existence profileExistence();
    }
}
