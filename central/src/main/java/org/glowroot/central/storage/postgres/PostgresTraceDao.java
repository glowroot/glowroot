/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central.storage.postgres;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import org.checkerframework.checker.nullness.qual.Nullable;
import javax.sql.DataSource;

import com.google.protobuf.AbstractMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.glowroot.central.repo.TraceDao;
import org.glowroot.central.util.Messages;
import org.glowroot.common.live.ImmutableEntries;
import org.glowroot.common.live.ImmutableEntriesAndQueries;
import org.glowroot.common.live.ImmutableQueries;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository.Entries;
import org.glowroot.common.live.LiveTraceRepository.EntriesAndQueries;
import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.live.LiveTraceRepository.Queries;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.model.Result;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ImmutableErrorMessageResult;
import org.glowroot.common2.repo.ImmutableHeaderPlus;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

/**
 * Minimal Postgres {@link TraceDao}: leaf {@code agent_id} rows only (no parent rollup indexes).
 */
public class PostgresTraceDao implements TraceDao {

    private final DataSource dataSource;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final Executor asyncExecutor;

    public PostgresTraceDao(DataSource dataSource, ConfigRepository configRepository, Clock clock,
            Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.configRepository = configRepository;
        this.clock = clock;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    @CheckReturnValue
    public CompletionStage<?> store(String agentId, Trace trace) {
        Trace.Header header = trace.getHeader();
        return configRepository.getCentralStorageConfig().thenCompose(storage -> {
            int ttlSeconds = storage.getTraceTTL();
            Timestamp expiresAt =
                    PostgresExpires.fromTtlSeconds(ttlSeconds, header.getCaptureTime(), clock);
            byte[] headerBytes = header.toByteArray();
            byte[] entriesBytes = trace.getEntryCount() == 0 ? null
                    : serializeMessages(trace.getEntryList());
            byte[] queriesBytes = trace.getQueryCount() == 0 ? null
                    : serializeMessages(trace.getQueryList());
            byte[] sharedBytes = trace.getSharedQueryTextCount() == 0 ? null
                    : serializeMessages(trace.getSharedQueryTextList());
            byte[] mainProfileBytes =
                    trace.hasMainThreadProfile() ? trace.getMainThreadProfile().toByteArray()
                            : null;
            byte[] auxProfileBytes =
                    trace.hasAuxThreadProfile() ? trace.getAuxThreadProfile().toByteArray() : null;
            String errorMessage =
                    header.hasError() ? header.getError().getMessage() : null;
            CompletableFuture<Void> future = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try (Connection connection = dataSource.getConnection();
                        PreparedStatement ps = connection.prepareStatement(
                                "insert into trace (agent_id, trace_id, capture_time, "
                                        + "duration_nanos, slow, error, partial, transaction_type, "
                                        + "transaction_name, headline, user_col, error_message, "
                                        + "header_bytes, entries_bytes, queries_bytes, "
                                        + "shared_query_texts_bytes, main_profile_bytes, "
                                        + "aux_profile_bytes, expires_at) values "
                                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                        + "on conflict (agent_id, trace_id) do update set "
                                        + "capture_time = excluded.capture_time, "
                                        + "duration_nanos = excluded.duration_nanos, "
                                        + "slow = excluded.slow, error = excluded.error, "
                                        + "partial = excluded.partial, "
                                        + "transaction_type = excluded.transaction_type, "
                                        + "transaction_name = excluded.transaction_name, "
                                        + "headline = excluded.headline, "
                                        + "user_col = excluded.user_col, "
                                        + "error_message = excluded.error_message, "
                                        + "header_bytes = excluded.header_bytes, "
                                        + "entries_bytes = excluded.entries_bytes, "
                                        + "queries_bytes = excluded.queries_bytes, "
                                        + "shared_query_texts_bytes = excluded.shared_query_texts_bytes, "
                                        + "main_profile_bytes = excluded.main_profile_bytes, "
                                        + "aux_profile_bytes = excluded.aux_profile_bytes, "
                                        + "expires_at = excluded.expires_at")) {
                    int i = 1;
                    ps.setString(i++, agentId);
                    ps.setString(i++, trace.getId());
                    ps.setTimestamp(i++, new Timestamp(header.getCaptureTime()));
                    ps.setLong(i++, header.getDurationNanos());
                    ps.setBoolean(i++, header.getSlow());
                    ps.setBoolean(i++, header.hasError());
                    ps.setBoolean(i++, header.getPartial());
                    ps.setString(i++, header.getTransactionType());
                    ps.setString(i++, header.getTransactionName());
                    ps.setString(i++, header.getHeadline());
                    ps.setString(i++, header.getUser());
                    if (errorMessage == null) {
                        ps.setNull(i++, Types.VARCHAR);
                    } else {
                        ps.setString(i++, errorMessage);
                    }
                    ps.setBytes(i++, headerBytes);
                    setBytesOrNull(ps, i++, entriesBytes);
                    setBytesOrNull(ps, i++, queriesBytes);
                    setBytesOrNull(ps, i++, sharedBytes);
                    setBytesOrNull(ps, i++, mainProfileBytes);
                    setBytesOrNull(ps, i++, auxProfileBytes);
                    ps.setTimestamp(i, expiresAt);
                    ps.executeUpdate();
                    future.complete(null);
                } catch (SQLException e) {
                    future.completeExceptionally(e);
                }
            }, asyncExecutor);
            return future;
        });
    }

    @Override
    public CompletionStage<Long> readSlowCount(String agentRollupId, TraceQuery query) {
        return readCount(agentRollupId, query, true);
    }

    @Override
    public CompletionStage<Result<TracePoint>> readSlowPoints(String agentRollupId,
            TraceQuery query, TracePointFilter filter, int limit) {
        return readPoints(agentRollupId, query, filter, limit, true);
    }

    @Override
    public CompletionStage<Long> readErrorCount(String agentRollupId, TraceQuery query) {
        return readCount(agentRollupId, query, false);
    }

    @Override
    public CompletionStage<Result<TracePoint>> readErrorPoints(String agentRollupId,
            TraceQuery query, TracePointFilter filter, int limit) {
        return readPoints(agentRollupId, query, filter, limit, false);
    }

    @Override
    public CompletionStage<ErrorMessageResult> readErrorMessages(String agentRollupId,
            TraceQuery query, ErrorMessageFilter filter, long resolutionMillis, int limit) {
        return CompletableFuture.completedFuture(ImmutableErrorMessageResult.builder()
                .addAllPoints(Collections.emptyList())
                .counts(new Result<>(Collections.emptyList(), false))
                .build());
    }

    @Override
    public CompletionStage<Long> readErrorMessageCount(String agentRollupId, TraceQuery query,
            String errorMessageFilter, CassandraProfile profile) {
        return CompletableFuture.completedFuture(0L);
    }

    @Override
    public CompletionStage<HeaderPlus> readHeaderPlus(String agentId, String traceId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select header_bytes, entries_bytes, queries_bytes, "
                                    + "main_profile_bytes, aux_profile_bytes from trace "
                                    + "where agent_id = ? and trace_id = ? and expires_at > ?")) {
                ps.setString(1, agentId);
                ps.setString(2, traceId);
                ps.setTimestamp(3, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Trace.Header header = Trace.Header.parseFrom(rs.getBytes(1));
                    Existence entriesExistence =
                            rs.getBytes(2) == null ? Existence.NO : Existence.YES;
                    Existence queriesExistence =
                            rs.getBytes(3) == null ? Existence.NO : Existence.YES;
                    Existence profileExistence = rs.getBytes(4) == null && rs.getBytes(5) == null
                            ? Existence.NO
                            : Existence.YES;
                    return ImmutableHeaderPlus.builder()
                            .header(header)
                            .entriesExistence(entriesExistence)
                            .queriesExistence(queriesExistence)
                            .profileExistence(profileExistence)
                            .build();
                }
            } catch (SQLException | InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<Entries> readEntries(String agentId, String traceId,
            CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select entries_bytes, shared_query_texts_bytes from trace "
                                    + "where agent_id = ? and trace_id = ? and expires_at > ?")) {
                ps.setString(1, agentId);
                ps.setString(2, traceId);
                ps.setTimestamp(3, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    byte[] entriesBytes = rs.getBytes(1);
                    if (entriesBytes == null) {
                        return null;
                    }
                    return ImmutableEntries.builder()
                            .addAllEntries(parseMessages(entriesBytes, Trace.Entry.parser()))
                            .addAllSharedQueryTexts(
                                    parseMessages(rs.getBytes(2), Trace.SharedQueryText.parser()))
                            .build();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<Queries> readQueries(String agentId, String traceId,
            CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select queries_bytes, shared_query_texts_bytes from trace "
                                    + "where agent_id = ? and trace_id = ? and expires_at > ?")) {
                ps.setString(1, agentId);
                ps.setString(2, traceId);
                ps.setTimestamp(3, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    byte[] queriesBytes = rs.getBytes(1);
                    if (queriesBytes == null) {
                        return null;
                    }
                    return ImmutableQueries.builder()
                            .addAllQueries(parseMessages(queriesBytes, Aggregate.Query.parser()))
                            .addAllSharedQueryTexts(
                                    parseMessages(rs.getBytes(2), Trace.SharedQueryText.parser()))
                            .build();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<EntriesAndQueries> readEntriesAndQueriesForExport(String agentId,
            String traceId, CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select entries_bytes, queries_bytes, shared_query_texts_bytes "
                                    + "from trace where agent_id = ? and trace_id = ? "
                                    + "and expires_at > ?")) {
                ps.setString(1, agentId);
                ps.setString(2, traceId);
                ps.setTimestamp(3, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    byte[] entriesBytes = rs.getBytes(1);
                    if (entriesBytes == null) {
                        return null;
                    }
                    return ImmutableEntriesAndQueries.builder()
                            .addAllEntries(parseMessages(entriesBytes, Trace.Entry.parser()))
                            .addAllQueries(parseMessages(rs.getBytes(2), Aggregate.Query.parser()))
                            .addAllSharedQueryTexts(
                                    parseMessages(rs.getBytes(3), Trace.SharedQueryText.parser()))
                            .build();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<Profile> readMainThreadProfile(String agentId, String traceId) {
        return readProfile(agentId, traceId, "main_profile_bytes");
    }

    @Override
    public CompletionStage<Profile> readAuxThreadProfile(String agentId, String traceId) {
        return readProfile(agentId, traceId, "aux_profile_bytes");
    }

    @Override
    public void truncateAll() throws Exception {
        try (Connection connection = dataSource.getConnection();
                var st = connection.createStatement()) {
            st.execute("truncate trace");
        }
    }

    private CompletionStage<Profile> readProfile(String agentId, String traceId, String column) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select " + column + " from trace where agent_id = ? and trace_id = ? "
                                    + "and expires_at > ?")) {
                ps.setString(1, agentId);
                ps.setString(2, traceId);
                ps.setTimestamp(3, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    byte[] bytes = rs.getBytes(1);
                    if (bytes == null) {
                        return null;
                    }
                    return Profile.parseFrom(bytes);
                }
            } catch (SQLException | InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    private CompletionStage<Long> readCount(String agentRollupId, TraceQuery query, boolean slow) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "select count(*) from trace where agent_id = ? and transaction_type = ? "
                    + "and capture_time > ? and capture_time <= ? and expires_at > ? and "
                    + (slow ? "slow" : "error")
                    + (query.transactionName() == null ? "" : " and transaction_name = ?");
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(sql)) {
                bindQuery(ps, agentRollupId, query);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    private CompletionStage<Result<TracePoint>> readPoints(String agentRollupId, TraceQuery query,
            TracePointFilter filter, int limit, boolean slow) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "select agent_id, trace_id, capture_time, duration_nanos, partial, error, "
                    + "headline, user_col, error_message from trace "
                    + "where agent_id = ? and transaction_type = ? "
                    + "and capture_time > ? and capture_time <= ? and expires_at > ? and "
                    + (slow ? "slow" : "error")
                    + (query.transactionName() == null ? "" : " and transaction_name = ?")
                    + " order by duration_nanos desc limit ?";
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(sql)) {
                int i = bindQuery(ps, agentRollupId, query);
                ps.setInt(i, limit + 1);
                List<TracePoint> points = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long durationNanos = rs.getLong(4);
                        String headline = rs.getString(7);
                        String user = rs.getString(8);
                        String errorMessage = rs.getString(9);
                        if (errorMessage == null) {
                            errorMessage = "";
                        }
                        if (!filter.matchesDuration(durationNanos)
                                || !filter.matchesHeadline(headline)
                                || !filter.matchesError(errorMessage)
                                || !filter.matchesUser(user)
                                || !filter.matchesAttributes(Collections.emptyMap())) {
                            continue;
                        }
                        points.add(ImmutableTracePoint.builder()
                                .agentId(rs.getString(1))
                                .traceId(rs.getString(2))
                                .captureTime(rs.getTimestamp(3).getTime())
                                .durationNanos(durationNanos)
                                .partial(rs.getBoolean(5))
                                .error(rs.getBoolean(6))
                                .checkLiveTraces(false)
                                .build());
                    }
                }
                return Result.create(points, limit);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    private int bindQuery(PreparedStatement ps, String agentRollupId, TraceQuery query)
            throws SQLException {
        int i = 1;
        ps.setString(i++, agentRollupId);
        ps.setString(i++, query.transactionType());
        ps.setTimestamp(i++, new Timestamp(query.from()));
        ps.setTimestamp(i++, new Timestamp(query.to()));
        ps.setTimestamp(i++, new Timestamp(clock.currentTimeMillis()));
        if (query.transactionName() != null) {
            ps.setString(i++, query.transactionName());
        }
        return i;
    }

    private static void setBytesOrNull(PreparedStatement ps, int index, @Nullable byte[] bytes)
            throws SQLException {
        if (bytes == null) {
            ps.setNull(index, Types.BINARY);
        } else {
            ps.setBytes(index, bytes);
        }
    }

    private static byte[] serializeMessages(List<? extends AbstractMessage> messages) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (AbstractMessage message : messages) {
                message.writeDelimitedTo(out);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T extends AbstractMessage> List<T> parseMessages(@Nullable byte[] bytes,
            Parser<T> parser) {
        if (bytes == null) {
            return Collections.emptyList();
        }
        return Messages.parseDelimitedFrom(ByteBuffer.wrap(bytes), parser);
    }
}
