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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.sql.DataSource;

import com.google.common.primitives.Ints;
import com.google.protobuf.InvalidProtocolBufferException;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.glowroot.central.repo.AgentRollupIds;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.AggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.SummaryQuery;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionNameErrorSummaryCollector;
import org.glowroot.common.model.TransactionNameErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionNameSummaryCollector;
import org.glowroot.common.model.TransactionNameSummaryCollector.SummarySortOrder;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.MutableAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OldTransactionAggregate;

import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Postgres port of Cassandra {@code AggregateDaoImpl} — experimental slice: store/read/rollup for
 * overall + per-transaction aggregates (Aggregate protobuf + summary columns). Query/profile merge
 * from stored Aggregate bytes; full query-text DAO / child rollups still incomplete vs Cassandra.
 */
public class PostgresAggregateDao implements AggregateDao {

    private final DataSource dataSource;
    private final PostgresActiveAgentDao activeAgentDao;
    private final PostgresTransactionTypeDao transactionTypeDao;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final Executor asyncExecutor;

    public PostgresAggregateDao(DataSource dataSource, PostgresActiveAgentDao activeAgentDao,
            PostgresTransactionTypeDao transactionTypeDao, ConfigRepository configRepository,
            Clock clock, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.activeAgentDao = activeAgentDao;
        this.transactionTypeDao = transactionTypeDao;
        this.configRepository = configRepository;
        this.clock = clock;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    @CheckReturnValue
    public CompletionStage<?> store(String agentId, long captureTime,
            List<OldAggregatesByType> aggregatesByTypeList,
            List<Aggregate.SharedQueryText> initialSharedQueryTexts) {
        if (aggregatesByTypeList.isEmpty()) {
            return activeAgentDao.insert(agentId, captureTime);
        }
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentId);
        return configRepository.getCentralStorageConfig().thenCompose(storage -> {
            List<Integer> rollupExpirationHours = storage.rollupExpirationHours();
            int ttlSeconds = Ints.saturatedCast(HOURS.toSeconds(rollupExpirationHours.get(0)));
            Timestamp expiresAt =
                    PostgresExpires.fromTtlSeconds(ttlSeconds, captureTime, clock);
            byte[] sharedBytes = serializeSharedQueryTexts(initialSharedQueryTexts);
            CompletableFuture<Void> write = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        Set<String> transactionTypes = new HashSet<>();
                        for (OldAggregatesByType byType : aggregatesByTypeList) {
                            String transactionType = byType.getTransactionType();
                            transactionTypes.add(transactionType);
                            upsertOverall(connection, agentId, 0, transactionType, captureTime,
                                    byType.getOverallAggregate(), sharedBytes, expiresAt);
                            for (OldTransactionAggregate tn : byType.getTransactionAggregateList()) {
                                upsertTransaction(connection, agentId, 0, transactionType,
                                        tn.getTransactionName(), captureTime, tn.getAggregate(),
                                        sharedBytes, expiresAt);
                            }
                        }
                        insertNeedsRollup(connection, agentId, captureTime, transactionTypes,
                                expiresAt);
                        connection.commit();
                        write.complete(null);
                    } catch (SQLException e) {
                        connection.rollback();
                        throw e;
                    } finally {
                        connection.setAutoCommit(true);
                    }
                } catch (Exception e) {
                    write.completeExceptionally(e);
                }
            }, asyncExecutor);
            return write.thenCompose(v -> transactionTypeDao.store(agentRollupIds,
                    aggregatesByTypeList.get(0).getTransactionType()))
                    .thenCompose(v -> {
                        CompletionStage<?> chain = CompletableFuture.completedFuture(null);
                        for (OldAggregatesByType byType : aggregatesByTypeList) {
                            chain = chain.thenCompose(
                                    x -> transactionTypeDao.store(agentRollupIds,
                                            byType.getTransactionType()));
                        }
                        return chain;
                    }).thenCompose(v -> activeAgentDao.insert(agentId, captureTime));
        });
    }

    @Override
    public CompletionStage<?> rollup(String agentRollupId) throws Exception {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        return configRepository.getCentralStorageConfig().thenCompose(storage -> {
            List<Integer> rollupExpirationHours = storage.rollupExpirationHours();
            CompletableFuture<Void> future = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    for (int level = 1; level < rollupConfigs.size(); level++) {
                        rollupLevel(connection, agentRollupId, level, rollupConfigs,
                                rollupExpirationHours);
                    }
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }, asyncExecutor);
            return future;
        });
    }

    @Override
    public void truncateAll() throws Exception {
        try (Connection connection = dataSource.getConnection();
                var st = connection.createStatement()) {
            st.execute("truncate aggregate_overall");
            st.execute("truncate aggregate_transaction");
            st.execute("truncate aggregate_needs_rollup");
        }
    }

    @Override
    public CompletionStage<?> mergeOverallSummaryInto(String agentRollupId, SummaryQuery query,
            OverallSummaryCollector collector, CassandraProfile profile) {
        return CompletableFuture.runAsync(() -> {
            String sql = "select capture_time, total_duration_nanos, total_cpu_nanos, "
                    + "total_allocated_bytes, transaction_count from aggregate_overall "
                    + "where agent_rollup_id = ? and rollup_level = ? and transaction_type = ? "
                    + "and capture_time > ? and capture_time <= ? and expires_at > ? "
                    + "order by capture_time";
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(sql)) {
                bindSummary(ps, agentRollupId, query);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        collector.mergeSummary(rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                                rs.getLong(5), rs.getTimestamp(1).getTime());
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<?> mergeTransactionNameSummariesInto(String agentRollupId,
            SummaryQuery query, SummarySortOrder sortOrder, int limit,
            TransactionNameSummaryCollector collector, CassandraProfile profile) {
        return CompletableFuture.runAsync(() -> {
            String sql = "select capture_time, transaction_name, total_duration_nanos, "
                    + "total_cpu_nanos, total_allocated_bytes, transaction_count "
                    + "from aggregate_transaction where agent_rollup_id = ? and rollup_level = ? "
                    + "and transaction_type = ? and capture_time > ? and capture_time <= ? "
                    + "and expires_at > ?";
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(sql)) {
                bindSummary(ps, agentRollupId, query);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        collector.collect(rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                                rs.getDouble(5), rs.getLong(6), rs.getTimestamp(1).getTime());
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<?> mergeOverallErrorSummaryInto(String agentRollupId, SummaryQuery query,
            OverallErrorSummaryCollector collector, CassandraProfile profile) {
        return CompletableFuture.runAsync(() -> {
            String sql = "select capture_time, error_count, transaction_count from aggregate_overall "
                    + "where agent_rollup_id = ? and rollup_level = ? and transaction_type = ? "
                    + "and capture_time > ? and capture_time <= ? and expires_at > ? "
                    + "and error_count > 0 order by capture_time";
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(sql)) {
                bindSummary(ps, agentRollupId, query);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        collector.mergeErrorSummary(rs.getLong(2), rs.getLong(3),
                                rs.getTimestamp(1).getTime());
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<?> mergeTransactionNameErrorSummariesInto(String agentRollupId,
            SummaryQuery query, ErrorSummarySortOrder sortOrder, int limit,
            TransactionNameErrorSummaryCollector collector, CassandraProfile profile) {
        return CompletableFuture.runAsync(() -> {
            String sql = "select capture_time, transaction_name, error_count, transaction_count "
                    + "from aggregate_transaction where agent_rollup_id = ? and rollup_level = ? "
                    + "and transaction_type = ? and capture_time > ? and capture_time <= ? "
                    + "and expires_at > ? and error_count > 0";
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(sql)) {
                bindSummary(ps, agentRollupId, query);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        collector.collect(rs.getString(2), rs.getLong(3), rs.getLong(4),
                                rs.getTimestamp(1).getTime());
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<List<OverviewAggregate>> readOverviewAggregates(String agentRollupId,
            AggregateQuery query, CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> readAggregates(agentRollupId, query,
                (captureTime, aggregate) -> {
                    MutableAggregate m = new MutableAggregate(1000, 1000);
                    mergeAggregate(m, aggregate);
                    return m.toOverviewAggregate(captureTime);
                }), asyncExecutor);
    }

    @Override
    public CompletionStage<List<PercentileAggregate>> readPercentileAggregates(String agentRollupId,
            AggregateQuery query, CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> readAggregates(agentRollupId, query,
                (captureTime, aggregate) -> {
                    MutableAggregate m = new MutableAggregate(1000, 1000);
                    mergeAggregate(m, aggregate);
                    return m.toPercentileAggregate(captureTime);
                }), asyncExecutor);
    }

    @Override
    public CompletionStage<List<ThroughputAggregate>> readThroughputAggregates(String agentRollupId,
            AggregateQuery query, CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> readAggregates(agentRollupId, query,
                (captureTime, aggregate) -> ImmutableThroughputAggregate.builder()
                        .captureTime(captureTime)
                        .transactionCount(aggregate.getTransactionCount())
                        .errorCount(aggregate.getErrorCount())
                        .build()), asyncExecutor);
    }

    @Override
    public CompletionStage<?> mergeQueriesInto(String agentRollupId, AggregateQuery query,
            QueryCollector collector, CassandraProfile profile) {
        return CompletableFuture.runAsync(() -> {
            List<RowAgg> rows = loadAggregateRows(agentRollupId, query);
            for (RowAgg row : rows) {
                List<Aggregate.SharedQueryText> shared = deserializeSharedQueryTexts(row.sharedBytes);
                for (Aggregate.Query q : row.aggregate.getQueryList()) {
                    Aggregate.SharedQueryText sqt = shared.get(q.getSharedQueryTextIndex());
                    String truncated = sqt.getFullText().isEmpty()
                            ? sqt.getTruncatedText()
                            : sqt.getFullText();
                    String sha1 = sqt.getFullTextSha1().isEmpty() ? null : sqt.getFullTextSha1();
                    boolean hasRows = q.hasTotalRows();
                    long totalRows = hasRows ? q.getTotalRows().getValue() : 0;
                    collector.mergeQuery(q.getType(), truncated, sha1, q.getTotalDurationNanos(),
                            q.getExecutionCount(), hasRows, totalRows);
                }
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<?> mergeServiceCallsInto(String agentRollupId, AggregateQuery query,
            ServiceCallCollector collector, CassandraProfile profile) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> mergeMainThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector, CassandraProfile profile) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> mergeAuxThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector, CassandraProfile profile) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<String> readFullQueryText(String agentRollupId, String fullQueryTextSha1,
            CassandraProfile profile) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Boolean> hasMainThreadProfile(String agentRollupId, AggregateQuery query,
            CassandraProfile profile) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletionStage<Boolean> hasAuxThreadProfile(String agentRollupId, AggregateQuery query,
            CassandraProfile profile) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletionStage<Boolean> shouldHaveQueries(String agentRollupId, AggregateQuery query) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletionStage<Boolean> shouldHaveServiceCalls(String agentRollupId,
            AggregateQuery query) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletionStage<Boolean> shouldHaveMainThreadProfile(String agentRollupId,
            AggregateQuery query) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletionStage<Boolean> shouldHaveAuxThreadProfile(String agentRollupId,
            AggregateQuery query) {
        return CompletableFuture.completedFuture(false);
    }

    private void rollupLevel(Connection connection, String agentRollupId, int level,
            List<RollupConfig> rollupConfigs, List<Integer> rollupExpirationHours)
            throws Exception {
        long intervalMillis = rollupConfigs.get(level).intervalMillis();
        int fromLevel = level - 1;
        int ttlSeconds = Ints.saturatedCast(HOURS.toSeconds(rollupExpirationHours.get(level)));
        try (PreparedStatement readNeeds = connection.prepareStatement(
                "select capture_time, uniqueness, transaction_types from aggregate_needs_rollup "
                        + "where agent_rollup = ? and rollup_level = ?");
                PreparedStatement deleteNeeds = connection.prepareStatement(
                        "delete from aggregate_needs_rollup where agent_rollup = ? "
                                + "and rollup_level = ? and capture_time = ? and uniqueness = ?")) {
            readNeeds.setString(1, agentRollupId);
            readNeeds.setInt(2, level);
            try (ResultSet needs = readNeeds.executeQuery()) {
                while (needs.next()) {
                    long rollupCaptureTime = needs.getTimestamp(1).getTime();
                    UUID uniqueness = (UUID) needs.getObject(2);
                    String[] transactionTypes = (String[]) needs.getArray(3).getArray();
                    long from = rollupCaptureTime - intervalMillis;
                    for (String transactionType : transactionTypes) {
                        mergeAndWriteOverall(connection, agentRollupId, fromLevel, level,
                                transactionType, from, rollupCaptureTime, ttlSeconds);
                        mergeAndWriteTransactions(connection, agentRollupId, fromLevel, level,
                                transactionType, from, rollupCaptureTime, ttlSeconds);
                    }
                    deleteNeeds.setString(1, agentRollupId);
                    deleteNeeds.setInt(2, level);
                    deleteNeeds.setTimestamp(3, new Timestamp(rollupCaptureTime));
                    deleteNeeds.setObject(4, uniqueness);
                    deleteNeeds.executeUpdate();
                }
            }
        }
    }

    private void mergeAndWriteOverall(Connection connection, String agentRollupId, int fromLevel,
            int toLevel, String transactionType, long from, long to, int ttlSeconds)
            throws Exception {
        MutableAggregate merged = new MutableAggregate(1000, 1000);
        List<Aggregate.SharedQueryText> shared = null;
        String sql = "select aggregate_bytes, shared_query_texts_bytes from aggregate_overall "
                + "where agent_rollup_id = ? and rollup_level = ? and transaction_type = ? "
                + "and capture_time > ? and capture_time <= ? and expires_at > ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, agentRollupId);
            ps.setInt(2, fromLevel);
            ps.setString(3, transactionType);
            ps.setTimestamp(4, new Timestamp(from));
            ps.setTimestamp(5, new Timestamp(to));
            ps.setTimestamp(6, new Timestamp(clock.currentTimeMillis()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Aggregate aggregate = Aggregate.parseFrom(rs.getBytes(1));
                    byte[] sharedBytes = rs.getBytes(2);
                    if (shared == null && sharedBytes != null) {
                        shared = deserializeSharedQueryTexts(sharedBytes);
                    }
                    mergeAggregate(merged, aggregate);
                }
            }
        }
        if (merged.isEmpty()) {
            return;
        }
        Aggregate aggregate = toAggregate(merged, shared);
        byte[] sharedBytes = shared == null ? null : serializeSharedQueryTexts(shared);
        Timestamp expiresAt = PostgresExpires.fromTtlSeconds(ttlSeconds, to, clock);
        upsertOverall(connection, agentRollupId, toLevel, transactionType, to, aggregate,
                sharedBytes, expiresAt);
    }

    private void mergeAndWriteTransactions(Connection connection, String agentRollupId,
            int fromLevel, int toLevel, String transactionType, long from, long to, int ttlSeconds)
            throws Exception {
        Set<String> names = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "select distinct transaction_name from aggregate_transaction "
                        + "where agent_rollup_id = ? and rollup_level = ? and transaction_type = ? "
                        + "and capture_time > ? and capture_time <= ? and expires_at > ?")) {
            ps.setString(1, agentRollupId);
            ps.setInt(2, fromLevel);
            ps.setString(3, transactionType);
            ps.setTimestamp(4, new Timestamp(from));
            ps.setTimestamp(5, new Timestamp(to));
            ps.setTimestamp(6, new Timestamp(clock.currentTimeMillis()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        for (String transactionName : names) {
            MutableAggregate merged = new MutableAggregate(1000, 1000);
            List<Aggregate.SharedQueryText> shared = null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "select aggregate_bytes, shared_query_texts_bytes from aggregate_transaction "
                            + "where agent_rollup_id = ? and rollup_level = ? "
                            + "and transaction_type = ? and transaction_name = ? "
                            + "and capture_time > ? and capture_time <= ? and expires_at > ?")) {
                ps.setString(1, agentRollupId);
                ps.setInt(2, fromLevel);
                ps.setString(3, transactionType);
                ps.setString(4, transactionName);
                ps.setTimestamp(5, new Timestamp(from));
                ps.setTimestamp(6, new Timestamp(to));
                ps.setTimestamp(7, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Aggregate aggregate = Aggregate.parseFrom(rs.getBytes(1));
                        byte[] sharedBytes = rs.getBytes(2);
                        if (shared == null && sharedBytes != null) {
                            shared = deserializeSharedQueryTexts(sharedBytes);
                        }
                        mergeAggregate(merged, aggregate);
                    }
                }
            }
            if (merged.isEmpty()) {
                continue;
            }
            Aggregate aggregate = toAggregate(merged, shared);
            byte[] sharedBytes = shared == null ? null : serializeSharedQueryTexts(shared);
            Timestamp expiresAt = PostgresExpires.fromTtlSeconds(ttlSeconds, to, clock);
            upsertTransaction(connection, agentRollupId, toLevel, transactionType, transactionName,
                    to, aggregate, sharedBytes, expiresAt);
        }
    }

    private void insertNeedsRollup(Connection connection, String agentId, long captureTime,
            Set<String> transactionTypes, Timestamp expiresAt) throws SQLException {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        if (rollupConfigs.size() < 2) {
            return;
        }
        long intervalMillis = rollupConfigs.get(1).intervalMillis();
        long rollupCaptureTime = CaptureTimes.getRollup(captureTime, intervalMillis);
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into aggregate_needs_rollup (agent_rollup, rollup_level, capture_time, "
                        + "uniqueness, transaction_types, expires_at) values (?, 1, ?, ?, ?, ?)")) {
            ps.setString(1, agentId);
            ps.setTimestamp(2, new Timestamp(rollupCaptureTime));
            ps.setObject(3, UUID.randomUUID());
            ps.setArray(4, connection.createArrayOf("text", transactionTypes.toArray()));
            ps.setTimestamp(5, expiresAt);
            ps.executeUpdate();
        }
        // higher levels: when level N-1 is written by rollup, Cassandra queues next; for lab slice
        // also queue level 2+ based on same capture for first store path simplification
        for (int level = 2; level < rollupConfigs.size(); level++) {
            long higherInterval = rollupConfigs.get(level).intervalMillis();
            long higherCapture = CaptureTimes.getRollup(captureTime, higherInterval);
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into aggregate_needs_rollup (agent_rollup, rollup_level, capture_time, "
                            + "uniqueness, transaction_types, expires_at) values (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, agentId);
                ps.setInt(2, level);
                ps.setTimestamp(3, new Timestamp(higherCapture));
                ps.setObject(4, UUID.randomUUID());
                ps.setArray(5, connection.createArrayOf("text", transactionTypes.toArray()));
                ps.setTimestamp(6, expiresAt);
                ps.executeUpdate();
            }
        }
    }

    private static void upsertOverall(Connection connection, String agentRollupId, int level,
            String transactionType, long captureTime, Aggregate aggregate, byte[] sharedBytes,
            Timestamp expiresAt) throws SQLException {
        SummaryNums n = summaryNums(aggregate);
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into aggregate_overall (agent_rollup_id, rollup_level, transaction_type, "
                        + "capture_time, total_duration_nanos, total_cpu_nanos, "
                        + "total_allocated_bytes, transaction_count, error_count, aggregate_bytes, "
                        + "shared_query_texts_bytes, expires_at) values (?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "on conflict (agent_rollup_id, rollup_level, transaction_type, capture_time) "
                        + "do update set total_duration_nanos = excluded.total_duration_nanos, "
                        + "total_cpu_nanos = excluded.total_cpu_nanos, "
                        + "total_allocated_bytes = excluded.total_allocated_bytes, "
                        + "transaction_count = excluded.transaction_count, "
                        + "error_count = excluded.error_count, "
                        + "aggregate_bytes = excluded.aggregate_bytes, "
                        + "shared_query_texts_bytes = excluded.shared_query_texts_bytes, "
                        + "expires_at = excluded.expires_at")) {
            bindUpsert(ps, agentRollupId, level, transactionType, null, captureTime, aggregate,
                    sharedBytes, expiresAt, n);
            ps.executeUpdate();
        }
    }

    private static void upsertTransaction(Connection connection, String agentRollupId, int level,
            String transactionType, String transactionName, long captureTime, Aggregate aggregate,
            byte[] sharedBytes, Timestamp expiresAt) throws SQLException {
        SummaryNums n = summaryNums(aggregate);
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into aggregate_transaction (agent_rollup_id, rollup_level, transaction_type, "
                        + "transaction_name, capture_time, total_duration_nanos, total_cpu_nanos, "
                        + "total_allocated_bytes, transaction_count, error_count, aggregate_bytes, "
                        + "shared_query_texts_bytes, expires_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "on conflict (agent_rollup_id, rollup_level, transaction_type, "
                        + "transaction_name, capture_time) do update set "
                        + "total_duration_nanos = excluded.total_duration_nanos, "
                        + "total_cpu_nanos = excluded.total_cpu_nanos, "
                        + "total_allocated_bytes = excluded.total_allocated_bytes, "
                        + "transaction_count = excluded.transaction_count, "
                        + "error_count = excluded.error_count, "
                        + "aggregate_bytes = excluded.aggregate_bytes, "
                        + "shared_query_texts_bytes = excluded.shared_query_texts_bytes, "
                        + "expires_at = excluded.expires_at")) {
            bindUpsert(ps, agentRollupId, level, transactionType, transactionName, captureTime,
                    aggregate, sharedBytes, expiresAt, n);
            ps.executeUpdate();
        }
    }

    private static void bindUpsert(PreparedStatement ps, String agentRollupId, int level,
            String transactionType, @Nullable String transactionName, long captureTime,
            Aggregate aggregate, byte[] sharedBytes, Timestamp expiresAt, SummaryNums n)
            throws SQLException {
        int i = 1;
        ps.setString(i++, agentRollupId);
        ps.setInt(i++, level);
        ps.setString(i++, transactionType);
        if (transactionName != null) {
            ps.setString(i++, transactionName);
        }
        ps.setTimestamp(i++, new Timestamp(captureTime));
        ps.setDouble(i++, n.totalDurationNanos);
        ps.setDouble(i++, n.totalCpuNanos);
        ps.setDouble(i++, n.totalAllocatedBytes);
        ps.setLong(i++, n.transactionCount);
        ps.setLong(i++, n.errorCount);
        ps.setBytes(i++, aggregate.toByteArray());
        ps.setBytes(i++, sharedBytes);
        ps.setTimestamp(i++, expiresAt);
    }

    private void bindSummary(PreparedStatement ps, String agentRollupId, SummaryQuery query)
            throws SQLException {
        ps.setString(1, agentRollupId);
        ps.setInt(2, query.rollupLevel());
        ps.setString(3, query.transactionType());
        ps.setTimestamp(4, new Timestamp(query.from()));
        ps.setTimestamp(5, new Timestamp(query.to()));
        ps.setTimestamp(6, new Timestamp(clock.currentTimeMillis()));
    }

    private <T> List<T> readAggregates(String agentRollupId, AggregateQuery query,
            AggMapper<T> mapper) {
        List<T> out = new ArrayList<>();
        for (RowAgg row : loadAggregateRows(agentRollupId, query)) {
            out.add(mapper.map(row.captureTime, row.aggregate));
        }
        return out;
    }

    private List<RowAgg> loadAggregateRows(String agentRollupId, AggregateQuery query) {
        boolean byName = query.transactionName() != null;
        String sql = byName
                ? "select capture_time, aggregate_bytes, shared_query_texts_bytes "
                        + "from aggregate_transaction where agent_rollup_id = ? and rollup_level = ? "
                        + "and transaction_type = ? and transaction_name = ? "
                        + "and capture_time >= ? and capture_time <= ? and expires_at > ? "
                        + "order by capture_time"
                : "select capture_time, aggregate_bytes, shared_query_texts_bytes "
                        + "from aggregate_overall where agent_rollup_id = ? and rollup_level = ? "
                        + "and transaction_type = ? and capture_time >= ? and capture_time <= ? "
                        + "and expires_at > ? order by capture_time";
        List<RowAgg> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, agentRollupId);
            ps.setInt(i++, query.rollupLevel());
            ps.setString(i++, query.transactionType());
            if (byName) {
                ps.setString(i++, query.transactionName());
            }
            ps.setTimestamp(i++, new Timestamp(query.from()));
            ps.setTimestamp(i++, new Timestamp(query.to()));
            ps.setTimestamp(i++, new Timestamp(clock.currentTimeMillis()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new RowAgg(rs.getTimestamp(1).getTime(),
                            Aggregate.parseFrom(rs.getBytes(2)), rs.getBytes(3)));
                }
            }
        } catch (SQLException | InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
        return rows;
    }

    private static void mergeAggregate(MutableAggregate m, Aggregate a) {
        m.addTotalDurationNanos(a.getTotalDurationNanos());
        m.addTransactionCount(a.getTransactionCount());
        m.addErrorCount(a.getErrorCount());
        m.addAsyncTransactions(a.getAsyncTransactions());
        m.mergeMainThreadRootTimers(a.getMainThreadRootTimerList());
        if (a.hasMainThreadStats()) {
            m.mergeMainThreadStats(a.getMainThreadStats());
        } else if (a.hasOldMainThreadStats()) {
            m.addMainThreadTotalCpuNanos(a.getOldMainThreadStats().getTotalCpuNanos().getValue());
            m.addMainThreadTotalAllocatedBytes(
                    a.getOldMainThreadStats().getTotalAllocatedBytes().getValue());
        }
        if (a.hasAuxThreadRootTimer()) {
            m.mergeAuxThreadRootTimer(a.getAuxThreadRootTimer());
            if (a.hasAuxThreadStats()) {
                m.mergeAuxThreadStats(a.getAuxThreadStats());
            }
        }
        m.mergeAsyncTimers(a.getAsyncTimerList());
        if (a.hasDurationNanosHistogram()) {
            m.mergeDurationNanosHistogram(a.getDurationNanosHistogram());
        }
        if (a.hasMainThreadProfile()) {
            m.mergeMainThreadProfile(a.getMainThreadProfile());
        }
        if (a.hasAuxThreadProfile()) {
            m.mergeAuxThreadProfile(a.getAuxThreadProfile());
        }
    }

    private static Aggregate toAggregate(MutableAggregate m,
            @Nullable List<Aggregate.SharedQueryText> shared) {
        OverviewAggregate overview = m.toOverviewAggregate(0);
        PercentileAggregate percentile = m.toPercentileAggregate(0);
        Aggregate.Builder b = Aggregate.newBuilder()
                .setTotalDurationNanos(overview.totalDurationNanos())
                .setTransactionCount(overview.transactionCount())
                .setErrorCount(m.getErrorCount())
                .setAsyncTransactions(overview.asyncTransactions())
                .addAllMainThreadRootTimer(overview.mainThreadRootTimers())
                .setMainThreadStats(overview.mainThreadStats())
                .addAllAsyncTimer(overview.asyncTimers())
                .setDurationNanosHistogram(percentile.durationNanosHistogram());
        if (overview.auxThreadRootTimer() != null) {
            b.setAuxThreadRootTimer(overview.auxThreadRootTimer());
        }
        if (overview.auxThreadStats() != null) {
            b.setAuxThreadStats(overview.auxThreadStats());
        }
        return b.build();
    }

    private static SummaryNums summaryNums(Aggregate aggregate) {
        double totalCpuNanos = 0;
        double totalAllocatedBytes = 0;
        if (aggregate.hasOldMainThreadStats()) {
            totalCpuNanos += aggregate.getOldMainThreadStats().getTotalCpuNanos().getValue();
            totalAllocatedBytes +=
                    aggregate.getOldMainThreadStats().getTotalAllocatedBytes().getValue();
        } else if (aggregate.hasMainThreadStats()) {
            totalCpuNanos += aggregate.getMainThreadStats().getTotalCpuNanos();
            totalAllocatedBytes += aggregate.getMainThreadStats().getTotalAllocatedBytes();
        }
        if (aggregate.hasAuxThreadRootTimer()) {
            if (aggregate.hasOldAuxThreadStats()) {
                totalCpuNanos += aggregate.getOldAuxThreadStats().getTotalCpuNanos().getValue();
                totalAllocatedBytes +=
                        aggregate.getOldAuxThreadStats().getTotalAllocatedBytes().getValue();
            } else if (aggregate.hasAuxThreadStats()) {
                totalCpuNanos += aggregate.getAuxThreadStats().getTotalCpuNanos();
                totalAllocatedBytes += aggregate.getAuxThreadStats().getTotalAllocatedBytes();
            }
        }
        return new SummaryNums(aggregate.getTotalDurationNanos(), totalCpuNanos,
                totalAllocatedBytes, aggregate.getTransactionCount(), aggregate.getErrorCount());
    }

    private static byte[] serializeSharedQueryTexts(List<Aggregate.SharedQueryText> texts) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Aggregate.SharedQueryText text : texts) {
                text.writeDelimitedTo(out);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Aggregate.SharedQueryText> deserializeSharedQueryTexts(
            @Nullable byte[] bytes) {
        List<Aggregate.SharedQueryText> texts = new ArrayList<>();
        if (bytes == null || bytes.length == 0) {
            return texts;
        }
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            while (in.available() > 0) {
                texts.add(Aggregate.SharedQueryText.parseDelimitedFrom(in));
            }
            return texts;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private interface AggMapper<T> {
        T map(long captureTime, Aggregate aggregate);
    }

    private static final class RowAgg {
        final long captureTime;
        final Aggregate aggregate;
        final byte[] sharedBytes;

        RowAgg(long captureTime, Aggregate aggregate, byte[] sharedBytes) {
            this.captureTime = captureTime;
            this.aggregate = aggregate;
            this.sharedBytes = sharedBytes;
        }
    }

    private static final class SummaryNums {
        final double totalDurationNanos;
        final double totalCpuNanos;
        final double totalAllocatedBytes;
        final long transactionCount;
        final long errorCount;

        SummaryNums(double totalDurationNanos, double totalCpuNanos, double totalAllocatedBytes,
                long transactionCount, long errorCount) {
            this.totalDurationNanos = totalDurationNanos;
            this.totalCpuNanos = totalCpuNanos;
            this.totalAllocatedBytes = totalAllocatedBytes;
            this.transactionCount = transactionCount;
            this.errorCount = errorCount;
        }
    }
}
