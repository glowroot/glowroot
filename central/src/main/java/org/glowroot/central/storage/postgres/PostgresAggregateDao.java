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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import javax.annotation.Nullable;
import javax.sql.DataSource;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Ints;
import com.google.protobuf.InvalidProtocolBufferException;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.glowroot.central.repo.AgentRollupIds;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.common.Constants;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.AggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.SummaryQuery;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.MutableServiceCall;
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
import org.glowroot.wire.api.model.Proto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Postgres port of Cassandra {@code AggregateDaoImpl} — experimental slice: store/read/rollup for
 * overall + per-transaction aggregates (Aggregate protobuf + summary columns). Queries / service
 * calls / profiles merge from Aggregate bytes on store, L0 read, and rollup/from-child.
 * FQT via {@link PostgresFullQueryTextDao}.
 */
public class PostgresAggregateDao implements AggregateDao {

    private static final HashFunction SHA_1 = Hashing.sha1();

    private final DataSource dataSource;
    private final PostgresActiveAgentDao activeAgentDao;
    private final PostgresTransactionTypeDao transactionTypeDao;
    private final PostgresFullQueryTextDao fullQueryTextDao;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final Executor asyncExecutor;

    public PostgresAggregateDao(DataSource dataSource, PostgresActiveAgentDao activeAgentDao,
            PostgresTransactionTypeDao transactionTypeDao,
            PostgresFullQueryTextDao fullQueryTextDao, ConfigRepository configRepository,
            Clock clock, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.activeAgentDao = activeAgentDao;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;
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
        List<CompletionStage<?>> fqtFutures = new ArrayList<>();
        List<Aggregate.SharedQueryText> sharedQueryTexts = new ArrayList<>();
        for (Aggregate.SharedQueryText sharedQueryText : initialSharedQueryTexts) {
            String fullTextSha1 = sharedQueryText.getFullTextSha1();
            if (fullTextSha1.isEmpty()) {
                String fullText = sharedQueryText.getFullText();
                if (fullText.length() > Constants.AGGREGATE_QUERY_TEXT_TRUNCATE) {
                    fullTextSha1 = SHA_1.hashString(fullText, UTF_8).toString();
                    fqtFutures.add(fullQueryTextDao.store(agentRollupIds, fullTextSha1, fullText));
                    sharedQueryText = Aggregate.SharedQueryText.newBuilder()
                            .setTruncatedText(fullText.substring(0,
                                    Constants.AGGREGATE_QUERY_TEXT_TRUNCATE))
                            .setFullTextSha1(fullTextSha1)
                            .build();
                }
            }
            sharedQueryTexts.add(sharedQueryText);
        }
        CompletableFuture<?>[] fqtArray = new CompletableFuture<?>[fqtFutures.size()];
        for (int i = 0; i < fqtFutures.size(); i++) {
            fqtArray[i] = fqtFutures.get(i).toCompletableFuture();
        }
        return CompletableFuture.allOf(fqtArray)
                .thenCompose(ignored -> configRepository.getCentralStorageConfig())
                .thenCompose(storage -> {
            List<Integer> rollupExpirationHours = storage.rollupExpirationHours();
            int ttlSeconds = Ints.saturatedCast(HOURS.toSeconds(rollupExpirationHours.get(0)));
            Timestamp expiresAt =
                    PostgresExpires.fromTtlSeconds(ttlSeconds, captureTime, clock);
            byte[] sharedBytes = serializeSharedQueryTexts(sharedQueryTexts);
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
                                ttlSeconds);
                        if (agentRollupIds.size() > 1) {
                            insertNeedsRollupFromChild(connection, agentRollupIds.get(1), agentId,
                                    captureTime, transactionTypes, ttlSeconds);
                        }
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
            List<String> transactionTypeNames = new ArrayList<>();
            for (OldAggregatesByType byType : aggregatesByTypeList) {
                transactionTypeNames.add(byType.getTransactionType());
            }
            return write.thenCompose(v -> {
                List<CompletableFuture<?>> typeFutures = new ArrayList<>();
                for (String transactionType : transactionTypeNames) {
                    typeFutures.add(transactionTypeDao.store(agentRollupIds, transactionType)
                            .toCompletableFuture());
                }
                return CompletableFuture.allOf(typeFutures.toArray(new CompletableFuture[0]));
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
                    connection.setAutoCommit(false);
                    try {
                        // Parent rollups (id ends with "::") merge children L0 first — Cassandra
                        // AggregateDaoImpl.rollupFromChildren parity.
                        if (agentRollupId.endsWith("::")) {
                            rollupFromChildren(connection, agentRollupId, rollupConfigs,
                                    rollupExpirationHours);
                        }
                        for (int level = 1; level < rollupConfigs.size(); level++) {
                            rollupLevel(connection, agentRollupId, level, rollupConfigs,
                                    rollupExpirationHours);
                        }
                        connection.commit();
                        future.complete(null);
                    } catch (Exception e) {
                        connection.rollback();
                        throw e;
                    } finally {
                        connection.setAutoCommit(true);
                    }
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
            st.execute("truncate aggregate_needs_rollup_from_child");
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
                    int idx = q.getSharedQueryTextIndex();
                    if (idx < 0 || idx >= shared.size()) {
                        continue;
                    }
                    Aggregate.SharedQueryText sqt = shared.get(idx);
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
        return CompletableFuture.runAsync(() -> {
            List<RowAgg> rows = loadAggregateRows(agentRollupId, query);
            for (RowAgg row : rows) {
                for (Aggregate.ServiceCall sc : row.aggregate.getServiceCallList()) {
                    collector.mergeServiceCall(sc.getType(), sc.getText(),
                            sc.getTotalDurationNanos(), sc.getExecutionCount());
                }
                for (Aggregate.OldServiceCallsByType byType : row.aggregate
                        .getOldServiceCallsByTypeList()) {
                    for (Aggregate.OldServiceCall sc : byType.getServiceCallList()) {
                        collector.mergeServiceCall(byType.getType(), sc.getText(),
                                sc.getTotalDurationNanos(), sc.getExecutionCount());
                    }
                }
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<?> mergeMainThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector, CassandraProfile profile) {
        return CompletableFuture.runAsync(() -> {
            List<RowAgg> rows = loadAggregateRows(agentRollupId, query);
            for (RowAgg row : rows) {
                if (row.aggregate.hasMainThreadProfile()) {
                    collector.mergeProfile(row.aggregate.getMainThreadProfile());
                    collector.updateLastCaptureTime(row.captureTime);
                }
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<?> mergeAuxThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector, CassandraProfile profile) {
        return CompletableFuture.runAsync(() -> {
            List<RowAgg> rows = loadAggregateRows(agentRollupId, query);
            for (RowAgg row : rows) {
                if (row.aggregate.hasAuxThreadProfile()) {
                    collector.mergeProfile(row.aggregate.getAuxThreadProfile());
                    collector.updateLastCaptureTime(row.captureTime);
                }
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<String> readFullQueryText(String agentRollupId, String fullQueryTextSha1,
            CassandraProfile profile) {
        return fullQueryTextDao.getFullText(agentRollupId, fullQueryTextSha1, profile);
    }

    @Override
    public CompletionStage<Boolean> hasMainThreadProfile(String agentRollupId, AggregateQuery query,
            CassandraProfile profile) {
        return CompletableFuture.supplyAsync(
                () -> anyAggregate(agentRollupId, query, Aggregate::hasMainThreadProfile),
                asyncExecutor);
    }

    @Override
    public CompletionStage<Boolean> hasAuxThreadProfile(String agentRollupId, AggregateQuery query,
            CassandraProfile profile) {
        return CompletableFuture.supplyAsync(
                () -> anyAggregate(agentRollupId, query, Aggregate::hasAuxThreadProfile),
                asyncExecutor);
    }

    @Override
    public CompletionStage<Boolean> shouldHaveQueries(String agentRollupId, AggregateQuery query) {
        return CompletableFuture.supplyAsync(() -> anyAggregate(agentRollupId, query,
                a -> !a.getQueryList().isEmpty() || !a.getOldQueriesByTypeList().isEmpty()),
                asyncExecutor);
    }

    @Override
    public CompletionStage<Boolean> shouldHaveServiceCalls(String agentRollupId,
            AggregateQuery query) {
        return CompletableFuture.supplyAsync(() -> anyAggregate(agentRollupId, query,
                a -> !a.getServiceCallList().isEmpty()
                        || !a.getOldServiceCallsByTypeList().isEmpty()),
                asyncExecutor);
    }

    @Override
    public CompletionStage<Boolean> shouldHaveMainThreadProfile(String agentRollupId,
            AggregateQuery query) {
        return hasMainThreadProfile(agentRollupId, query, CassandraProfile.web);
    }

    @Override
    public CompletionStage<Boolean> shouldHaveAuxThreadProfile(String agentRollupId,
            AggregateQuery query) {
        return hasAuxThreadProfile(agentRollupId, query, CassandraProfile.web);
    }

    private void rollupFromChildren(Connection connection, String agentRollupId,
            List<RollupConfig> rollupConfigs, List<Integer> rollupExpirationHours)
            throws Exception {
        int ttlSeconds = Ints.saturatedCast(HOURS.toSeconds(rollupExpirationHours.get(0)));
        long currentTimeMillis = clock.currentTimeMillis();
        String parentAgentRollupId = AgentRollupIds.getParent(agentRollupId);
        Map<Long, NeedsRollupFromChildBatch> batches = new LinkedHashMap<>();
        try (PreparedStatement readNeeds = connection.prepareStatement(
                "select capture_time, uniqueness, child_agent_rollup, transaction_types "
                        + "from aggregate_needs_rollup_from_child "
                        + "where agent_rollup = ? and expires_at > ? order by capture_time")) {
            readNeeds.setString(1, agentRollupId);
            readNeeds.setTimestamp(2, new Timestamp(currentTimeMillis));
            try (ResultSet needs = readNeeds.executeQuery()) {
                while (needs.next()) {
                    long captureTime = needs.getTimestamp(1).getTime();
                    UUID uniqueness = (UUID) needs.getObject(2);
                    String childAgentRollup = needs.getString(3);
                    String[] transactionTypes = (String[]) needs.getArray(4).getArray();
                    NeedsRollupFromChildBatch batch = batches.get(captureTime);
                    if (batch == null) {
                        batch = new NeedsRollupFromChildBatch(captureTime);
                        batches.put(captureTime, batch);
                    }
                    batch.uniquenessKeys.add(uniqueness);
                    for (String transactionType : transactionTypes) {
                        batch.transactionTypes.add(transactionType);
                        batch.childrenByType
                                .computeIfAbsent(transactionType, k -> new HashSet<>())
                                .add(childAgentRollup);
                    }
                }
            }
        }
        try (PreparedStatement deleteNeeds = connection.prepareStatement(
                "delete from aggregate_needs_rollup_from_child where agent_rollup = ? "
                        + "and capture_time = ? and uniqueness = ?")) {
            for (NeedsRollupFromChildBatch batch : batches.values()) {
                for (Map.Entry<String, Set<String>> entry : batch.childrenByType.entrySet()) {
                    mergeOverallFromChildren(connection, agentRollupId, entry.getKey(),
                            entry.getValue(), batch.captureTime, ttlSeconds);
                    mergeTransactionsFromChildren(connection, agentRollupId, entry.getKey(),
                            entry.getValue(), batch.captureTime, ttlSeconds);
                }
                if (parentAgentRollupId != null) {
                    insertNeedsRollupFromChild(connection, parentAgentRollupId, agentRollupId,
                            batch.captureTime, batch.transactionTypes, ttlSeconds);
                }
                insertNeedsRollup(connection, agentRollupId, batch.captureTime,
                        batch.transactionTypes, ttlSeconds);
                for (UUID uniqueness : batch.uniquenessKeys) {
                    deleteNeeds.setString(1, agentRollupId);
                    deleteNeeds.setTimestamp(2, new Timestamp(batch.captureTime));
                    deleteNeeds.setObject(3, uniqueness);
                    deleteNeeds.executeUpdate();
                }
            }
        }
    }

    private void mergeOverallFromChildren(Connection connection, String parentAgentRollupId,
            String transactionType, Set<String> childAgentRollupIds, long captureTime,
            int ttlSeconds) throws Exception {
        MutableAggregate merged = new MutableAggregate(1000, 1000);
        for (String childId : childAgentRollupIds) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "select aggregate_bytes, shared_query_texts_bytes from aggregate_overall "
                            + "where agent_rollup_id = ? and rollup_level = 0 "
                            + "and transaction_type = ? and capture_time = ? and expires_at > ?")) {
                ps.setString(1, childId);
                ps.setString(2, transactionType);
                ps.setTimestamp(3, new Timestamp(captureTime));
                ps.setTimestamp(4, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Aggregate aggregate = Aggregate.parseFrom(rs.getBytes(1));
                        mergeAggregate(merged, aggregate,
                                deserializeSharedQueryTexts(rs.getBytes(2)));
                    }
                }
            }
        }
        if (merged.isEmpty()) {
            return;
        }
        RolledAggregate rolled = toAggregate(merged);
        Timestamp expiresAt = PostgresExpires.fromTtlSeconds(ttlSeconds, captureTime, clock);
        upsertOverall(connection, parentAgentRollupId, 0, transactionType, captureTime,
                rolled.aggregate, rolled.sharedBytes, expiresAt);
    }

    private void mergeTransactionsFromChildren(Connection connection, String parentAgentRollupId,
            String transactionType, Set<String> childAgentRollupIds, long captureTime,
            int ttlSeconds) throws Exception {
        Set<String> names = new HashSet<>();
        for (String childId : childAgentRollupIds) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "select distinct transaction_name from aggregate_transaction "
                            + "where agent_rollup_id = ? and rollup_level = 0 "
                            + "and transaction_type = ? and capture_time = ? and expires_at > ?")) {
                ps.setString(1, childId);
                ps.setString(2, transactionType);
                ps.setTimestamp(3, new Timestamp(captureTime));
                ps.setTimestamp(4, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            }
        }
        for (String transactionName : names) {
            MutableAggregate merged = new MutableAggregate(1000, 1000);
            for (String childId : childAgentRollupIds) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "select aggregate_bytes, shared_query_texts_bytes from "
                                + "aggregate_transaction where agent_rollup_id = ? "
                                + "and rollup_level = 0 and transaction_type = ? "
                                + "and transaction_name = ? and capture_time = ? "
                                + "and expires_at > ?")) {
                    ps.setString(1, childId);
                    ps.setString(2, transactionType);
                    ps.setString(3, transactionName);
                    ps.setTimestamp(4, new Timestamp(captureTime));
                    ps.setTimestamp(5, new Timestamp(clock.currentTimeMillis()));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            Aggregate aggregate = Aggregate.parseFrom(rs.getBytes(1));
                            mergeAggregate(merged, aggregate,
                                    deserializeSharedQueryTexts(rs.getBytes(2)));
                        }
                    }
                }
            }
            if (merged.isEmpty()) {
                continue;
            }
            RolledAggregate rolled = toAggregate(merged);
            Timestamp expiresAt = PostgresExpires.fromTtlSeconds(ttlSeconds, captureTime, clock);
            upsertTransaction(connection, parentAgentRollupId, 0, transactionType, transactionName,
                    captureTime, rolled.aggregate, rolled.sharedBytes, expiresAt);
        }
    }

    private void insertNeedsRollupFromChild(Connection connection, String parentAgentRollupId,
            String childAgentRollupId, long captureTime, Set<String> transactionTypes,
            int dataTtlSeconds) throws SQLException {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        int needsTtlSeconds =
                PostgresExpires.needsRollupAdjustedTtlSeconds(dataTtlSeconds, rollupConfigs);
        Timestamp needsExpiresAt =
                PostgresExpires.fromTtlSeconds(needsTtlSeconds, captureTime, clock);
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into aggregate_needs_rollup_from_child (agent_rollup, capture_time, "
                        + "uniqueness, child_agent_rollup, transaction_types, expires_at) "
                        + "values (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, parentAgentRollupId);
            ps.setTimestamp(2, new Timestamp(captureTime));
            ps.setObject(3, UUID.randomUUID());
            ps.setString(4, childAgentRollupId);
            ps.setArray(5, connection.createArrayOf("text", transactionTypes.toArray()));
            ps.setTimestamp(6, needsExpiresAt);
            ps.executeUpdate();
        }
    }

    private void rollupLevel(Connection connection, String agentRollupId, int level,
            List<RollupConfig> rollupConfigs, List<Integer> rollupExpirationHours)
            throws Exception {
        long intervalMillis = rollupConfigs.get(level).intervalMillis();
        int fromLevel = level - 1;
        int ttlSeconds = Ints.saturatedCast(HOURS.toSeconds(rollupExpirationHours.get(level)));
        // Capture now before read — same race guard as Common.getNeedsRollupList.
        long currentTimeMillis = clock.currentTimeMillis();
        Map<Long, NeedsRollupBatch> batches = new LinkedHashMap<>();
        try (PreparedStatement readNeeds = connection.prepareStatement(
                "select capture_time, uniqueness, transaction_types from aggregate_needs_rollup "
                        + "where agent_rollup = ? and rollup_level = ? and expires_at > ? "
                        + "order by capture_time")) {
            readNeeds.setString(1, agentRollupId);
            readNeeds.setInt(2, level);
            readNeeds.setTimestamp(3, new Timestamp(currentTimeMillis));
            try (ResultSet needs = readNeeds.executeQuery()) {
                while (needs.next()) {
                    long rollupCaptureTime = needs.getTimestamp(1).getTime();
                    // Same rule as Common.isOldEnoughToRollup: skip open interval (break —
                    // rows ordered by capture_time).
                    if (rollupCaptureTime >= currentTimeMillis - intervalMillis) {
                        break;
                    }
                    UUID uniqueness = (UUID) needs.getObject(2);
                    String[] transactionTypes = (String[]) needs.getArray(3).getArray();
                    NeedsRollupBatch batch = batches.get(rollupCaptureTime);
                    if (batch == null) {
                        batch = new NeedsRollupBatch(rollupCaptureTime);
                        batches.put(rollupCaptureTime, batch);
                    }
                    batch.uniquenessKeys.add(uniqueness);
                    for (String transactionType : transactionTypes) {
                        batch.transactionTypes.add(transactionType);
                    }
                }
            }
        }
        try (PreparedStatement deleteNeeds = connection.prepareStatement(
                "delete from aggregate_needs_rollup where agent_rollup = ? "
                        + "and rollup_level = ? and capture_time = ? and uniqueness = ?")) {
            for (NeedsRollupBatch batch : batches.values()) {
                long from = batch.captureTime - intervalMillis;
                for (String transactionType : batch.transactionTypes) {
                    mergeAndWriteOverall(connection, agentRollupId, fromLevel, level,
                            transactionType, from, batch.captureTime, ttlSeconds);
                    mergeAndWriteTransactions(connection, agentRollupId, fromLevel, level,
                            transactionType, from, batch.captureTime, ttlSeconds);
                }
                queueNextNeedsRollup(connection, agentRollupId, level, batch.captureTime,
                        batch.transactionTypes.toArray(new String[0]), ttlSeconds);
                for (UUID uniqueness : batch.uniquenessKeys) {
                    deleteNeeds.setString(1, agentRollupId);
                    deleteNeeds.setInt(2, level);
                    deleteNeeds.setTimestamp(3, new Timestamp(batch.captureTime));
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
                    mergeAggregate(merged, aggregate,
                            deserializeSharedQueryTexts(rs.getBytes(2)));
                }
            }
        }
        if (merged.isEmpty()) {
            return;
        }
        RolledAggregate rolled = toAggregate(merged);
        Timestamp expiresAt = PostgresExpires.fromTtlSeconds(ttlSeconds, to, clock);
        upsertOverall(connection, agentRollupId, toLevel, transactionType, to, rolled.aggregate,
                rolled.sharedBytes, expiresAt);
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
                        mergeAggregate(merged, aggregate,
                                deserializeSharedQueryTexts(rs.getBytes(2)));
                    }
                }
            }
            if (merged.isEmpty()) {
                continue;
            }
            RolledAggregate rolled = toAggregate(merged);
            Timestamp expiresAt = PostgresExpires.fromTtlSeconds(ttlSeconds, to, clock);
            upsertTransaction(connection, agentRollupId, toLevel, transactionType, transactionName,
                    to, rolled.aggregate, rolled.sharedBytes, expiresAt);
        }
    }

    private void insertNeedsRollup(Connection connection, String agentId, long captureTime,
            Set<String> transactionTypes, int dataTtlSeconds) throws SQLException {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        if (rollupConfigs.size() < 2) {
            return;
        }
        // Only queue level 1 here (same as Cassandra). Higher levels are queued after lower
        // rollup succeeds — see queueNextNeedsRollup.
        long intervalMillis = rollupConfigs.get(1).intervalMillis();
        long rollupCaptureTime = CaptureTimes.getRollup(captureTime, intervalMillis);
        int needsTtlSeconds =
                PostgresExpires.needsRollupAdjustedTtlSeconds(dataTtlSeconds, rollupConfigs);
        Timestamp needsExpiresAt =
                PostgresExpires.fromTtlSeconds(needsTtlSeconds, rollupCaptureTime, clock);
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into aggregate_needs_rollup (agent_rollup, rollup_level, capture_time, "
                        + "uniqueness, transaction_types, expires_at) values (?, 1, ?, ?, ?, ?)")) {
            ps.setString(1, agentId);
            ps.setTimestamp(2, new Timestamp(rollupCaptureTime));
            ps.setObject(3, UUID.randomUUID());
            ps.setArray(4, connection.createArrayOf("text", transactionTypes.toArray()));
            ps.setTimestamp(5, needsExpiresAt);
            ps.executeUpdate();
        }
    }

    private void queueNextNeedsRollup(Connection connection, String agentRollupId, int fromLevel,
            long captureTime, String[] transactionTypes, int dataTtlSeconds) throws SQLException {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        int nextLevel = fromLevel + 1;
        if (nextLevel >= rollupConfigs.size()) {
            return;
        }
        long intervalMillis = rollupConfigs.get(nextLevel).intervalMillis();
        long rollupCaptureTime = CaptureTimes.getRollup(captureTime, intervalMillis);
        int needsTtlSeconds =
                PostgresExpires.needsRollupAdjustedTtlSeconds(dataTtlSeconds, rollupConfigs);
        Timestamp expiresAt =
                PostgresExpires.fromTtlSeconds(needsTtlSeconds, rollupCaptureTime, clock);
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into aggregate_needs_rollup (agent_rollup, rollup_level, capture_time, "
                        + "uniqueness, transaction_types, expires_at) values (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, agentRollupId);
            ps.setInt(2, nextLevel);
            ps.setTimestamp(3, new Timestamp(rollupCaptureTime));
            ps.setObject(4, UUID.randomUUID());
            ps.setArray(5, connection.createArrayOf("text", transactionTypes));
            ps.setTimestamp(6, expiresAt);
            ps.executeUpdate();
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

    /**
     * Stream aggregate_bytes only; stop on first match. Avoids loading every row + shared query
     * texts into memory for shouldHave* / has*Profile UI probes.
     */
    private boolean anyAggregate(String agentRollupId, AggregateQuery query,
            Predicate<Aggregate> match) {
        boolean byName = query.transactionName() != null;
        String sql = byName
                ? "select aggregate_bytes from aggregate_transaction "
                        + "where agent_rollup_id = ? and rollup_level = ? "
                        + "and transaction_type = ? and transaction_name = ? "
                        + "and capture_time >= ? and capture_time <= ? and expires_at > ? "
                        + "order by capture_time"
                : "select aggregate_bytes from aggregate_overall "
                        + "where agent_rollup_id = ? and rollup_level = ? "
                        + "and transaction_type = ? and capture_time >= ? and capture_time <= ? "
                        + "and expires_at > ? order by capture_time";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            bindAggregateQuery(ps, agentRollupId, query, byName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (match.test(Aggregate.parseFrom(rs.getBytes(1)))) {
                        return true;
                    }
                }
            }
        } catch (SQLException | InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
        return false;
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
            bindAggregateQuery(ps, agentRollupId, query, byName);
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

    private void bindAggregateQuery(PreparedStatement ps, String agentRollupId,
            AggregateQuery query, boolean byName) throws SQLException {
        int i = 1;
        ps.setString(i++, agentRollupId);
        ps.setInt(i++, query.rollupLevel());
        ps.setString(i++, query.transactionType());
        if (byName) {
            ps.setString(i++, query.transactionName());
        }
        ps.setTimestamp(i++, new Timestamp(query.from()));
        ps.setTimestamp(i++, new Timestamp(query.to()));
        ps.setTimestamp(i, new Timestamp(clock.currentTimeMillis()));
    }

    private static void mergeAggregate(MutableAggregate m, Aggregate a) {
        mergeAggregate(m, a, null);
    }

    private static void mergeAggregate(MutableAggregate m, Aggregate a,
            @Nullable List<Aggregate.SharedQueryText> shared) {
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
            // MutableAggregate.toOverviewAggregate requires auxThreadStats whenever the aux
            // root timer is present (Cassandra overview rows always materialize both).
            if (a.hasAuxThreadStats()) {
                m.mergeAuxThreadStats(a.getAuxThreadStats());
            } else {
                m.mergeAuxThreadStats(Aggregate.ThreadStats.getDefaultInstance());
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
        if (shared != null) {
            for (Aggregate.Query q : a.getQueryList()) {
                int idx = q.getSharedQueryTextIndex();
                if (idx < 0 || idx >= shared.size()) {
                    continue;
                }
                mergeSharedQuery(m, q.getType(), shared.get(idx), q.getTotalDurationNanos(),
                        q.getExecutionCount(), q.hasTotalRows(),
                        q.hasTotalRows() ? q.getTotalRows().getValue() : 0);
            }
            for (Aggregate.OldQueriesByType byType : a.getOldQueriesByTypeList()) {
                for (Aggregate.OldQuery q : byType.getQueryList()) {
                    int idx = q.getSharedQueryTextIndex();
                    if (idx < 0 || idx >= shared.size()) {
                        continue;
                    }
                    mergeSharedQuery(m, byType.getType(), shared.get(idx),
                            q.getTotalDurationNanos(), q.getExecutionCount(), q.hasTotalRows(),
                            q.hasTotalRows() ? q.getTotalRows().getValue() : 0);
                }
            }
        }
        for (Aggregate.ServiceCall sc : a.getServiceCallList()) {
            m.mergeServiceCall(sc.getType(), sc.getText(), sc.getTotalDurationNanos(),
                    sc.getExecutionCount());
        }
        for (Aggregate.OldServiceCallsByType byType : a.getOldServiceCallsByTypeList()) {
            for (Aggregate.OldServiceCall sc : byType.getServiceCallList()) {
                m.mergeServiceCall(byType.getType(), sc.getText(), sc.getTotalDurationNanos(),
                        sc.getExecutionCount());
            }
        }
    }

    private static void mergeSharedQuery(MutableAggregate m, String type,
            Aggregate.SharedQueryText sqt, double totalDurationNanos, long executionCount,
            boolean hasTotalRows, long totalRows) {
        String truncated;
        String sha1;
        if (sqt.getFullTextSha1().isEmpty()) {
            truncated = sqt.getFullText();
            sha1 = null;
        } else {
            truncated = sqt.getTruncatedText();
            sha1 = sqt.getFullTextSha1();
        }
        m.mergeQuery(type, truncated, sha1, totalDurationNanos, executionCount, hasTotalRows,
                totalRows);
    }

    private static RolledAggregate toAggregate(MutableAggregate m) {
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
        List<Aggregate.SharedQueryText> sharedOut = new ArrayList<>();
        QueryCollector queries = m.getQueries();
        if (queries != null) {
            Map<String, Integer> sharedIndex = new HashMap<>();
            for (MutableQuery mq : queries.getSortedAndTruncatedQueries()) {
                String key = mq.getFullTextSha1() != null
                        ? "sha1:" + mq.getFullTextSha1()
                        : "full:" + mq.getTruncatedText();
                Integer idx = sharedIndex.get(key);
                if (idx == null) {
                    idx = sharedOut.size();
                    sharedIndex.put(key, idx);
                    Aggregate.SharedQueryText.Builder sqt = Aggregate.SharedQueryText.newBuilder();
                    if (mq.getFullTextSha1() == null) {
                        sqt.setFullText(mq.getTruncatedText());
                    } else {
                        sqt.setTruncatedText(mq.getTruncatedText())
                                .setFullTextSha1(mq.getFullTextSha1());
                    }
                    sharedOut.add(sqt.build());
                }
                Aggregate.Query.Builder qb = Aggregate.Query.newBuilder()
                        .setType(mq.getType())
                        .setSharedQueryTextIndex(idx)
                        .setTotalDurationNanos(mq.getTotalDurationNanos())
                        .setExecutionCount(mq.getExecutionCount());
                if (mq.hasTotalRows()) {
                    qb.setTotalRows(Proto.OptionalInt64.newBuilder().setValue(mq.getTotalRows()));
                }
                b.addQuery(qb);
            }
        }
        ServiceCallCollector serviceCalls = m.getServiceCalls();
        if (serviceCalls != null) {
            for (MutableServiceCall sc : serviceCalls.getSortedAndTruncatedServiceCalls()) {
                b.addServiceCall(Aggregate.ServiceCall.newBuilder()
                        .setType(sc.getType())
                        .setText(sc.getText())
                        .setTotalDurationNanos(sc.getTotalDurationNanos())
                        .setExecutionCount(sc.getExecutionCount()));
            }
        }
        MutableProfile mainProfile = m.getMainThreadProfile();
        if (mainProfile != null) {
            b.setMainThreadProfile(mainProfile.toProto());
        }
        MutableProfile auxProfile = m.getAuxThreadProfile();
        if (auxProfile != null) {
            b.setAuxThreadProfile(auxProfile.toProto());
        }
        byte[] sharedBytes = sharedOut.isEmpty() ? null : serializeSharedQueryTexts(sharedOut);
        return new RolledAggregate(b.build(), sharedBytes);
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

    private static final class RolledAggregate {
        final Aggregate aggregate;
        final @Nullable byte[] sharedBytes;

        RolledAggregate(Aggregate aggregate, @Nullable byte[] sharedBytes) {
            this.aggregate = aggregate;
            this.sharedBytes = sharedBytes;
        }
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

    /** Coalesced needs_rollup rows for one capture_time (Cassandra Common.NeedsRollup parity). */
    private static final class NeedsRollupBatch {
        final long captureTime;
        final Set<String> transactionTypes = new HashSet<>();
        final Set<UUID> uniquenessKeys = new HashSet<>();

        NeedsRollupBatch(long captureTime) {
            this.captureTime = captureTime;
        }
    }

    /** Coalesced from_child needs for one capture_time. */
    private static final class NeedsRollupFromChildBatch {
        final long captureTime;
        final Set<String> transactionTypes = new HashSet<>();
        final Set<UUID> uniquenessKeys = new HashSet<>();
        final Map<String, Set<String>> childrenByType = new HashMap<>();

        NeedsRollupFromChildBatch(long captureTime) {
            this.captureTime = captureTime;
        }
    }
}
