/*
 * Copyright 2015 the original author or authors.
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

import java.sql.SQLException;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointQuery;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.ProfileTreeOuterClass.ProfileTree;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public interface TraceRepository {

    void collect(long serverId, Trace trace) throws Exception;

    Result<TracePoint> readPoints(TracePointQuery query) throws Exception;

    long readOverallSlowCount(long serverId, String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception;

    long readTransactionSlowCount(long serverId, String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws Exception;

    long readOverallErrorCount(long serverId, String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception;

    long readTransactionErrorCount(long serverId, String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws Exception;

    List<TraceErrorPoint> readErrorPoints(ErrorMessageQuery query, long resolutionMillis,
            long liveCaptureTime) throws Exception;

    Result<ErrorMessageCount> readErrorMessageCounts(ErrorMessageQuery query) throws Exception;

    @Nullable
    HeaderPlus readHeader(long serverId, String traceId) throws Exception;

    List<Trace.Entry> readEntries(long serverId, String traceId) throws Exception;

    @Nullable
    ProfileTree readProfileTree(long serverId, String traceId) throws Exception;

    void deleteAll(long serverId) throws SQLException;

    long count(long serverId) throws Exception;

    @Value.Immutable
    public interface ErrorMessageCount {
        String message();
        long count();
    }

    @Value.Immutable
    public interface ErrorMessageQuery {
        long serverId();
        String transactionType();
        @Nullable
        String transactionName();
        long from();
        long to();
        ImmutableList<String> includes();
        ImmutableList<String> excludes();
        int limit();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface TraceErrorPoint {
        long captureTime();
        long errorCount();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface HeaderPlus {
        Trace.Header header();
        Existence entriesExistence();
        Existence profileExistence();
    }
}
