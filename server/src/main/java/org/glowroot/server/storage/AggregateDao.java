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
package org.glowroot.server.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import org.immutables.value.Value;

import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Styles;
import org.glowroot.server.util.ByteBufferInputStream;
import org.glowroot.server.util.Messages;
import org.glowroot.storage.config.ConfigDefaults;
import org.glowroot.storage.repo.AgentRepository.AgentRollup;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.ImmutableOverallErrorSummary;
import org.glowroot.storage.repo.ImmutableOverallSummary;
import org.glowroot.storage.repo.ImmutableOverviewAggregate;
import org.glowroot.storage.repo.ImmutablePercentileAggregate;
import org.glowroot.storage.repo.ImmutableThroughputAggregate;
import org.glowroot.storage.repo.ImmutableTransactionQuery;
import org.glowroot.storage.repo.MutableAggregate;
import org.glowroot.storage.repo.MutableThreadStats;
import org.glowroot.storage.repo.MutableTimer;
import org.glowroot.storage.repo.ProfileCollector;
import org.glowroot.storage.repo.TransactionErrorSummaryCollector;
import org.glowroot.storage.repo.TransactionSummaryCollector;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.QueriesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.Timer;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.server.util.Checkers.castUntainted;

public class AggregateDao implements AggregateRepository {

    private static final Table summaryTable = ImmutableTable.builder()
            .partialName("summary")
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .summary(true)
            .fromInclusive(false)
            .build();

    private static final Table errorSummaryTable = ImmutableTable.builder()
            .partialName("error_summary")
            .addColumns(ImmutableColumn.of("error_count", "bigint"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .summary(true)
            .fromInclusive(false)
            .build();

    private static final Table overviewTable = ImmutableTable.builder()
            .partialName("overview")
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .addColumns(ImmutableColumn.of("main_thread_root_timers", "blob"))
            .addColumns(ImmutableColumn.of("aux_thread_root_timers", "blob"))
            .addColumns(ImmutableColumn.of("async_root_timers", "blob"))
            .addColumns(ImmutableColumn.of("main_thread_stats", "blob"))
            .addColumns(ImmutableColumn.of("aux_thread_stats", "blob"))
            .summary(false)
            .fromInclusive(true)
            .build();

    private static final Table histogramTable = ImmutableTable.builder()
            .partialName("histogram")
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .addColumns(ImmutableColumn.of("duration_nanos_histogram", "blob"))
            .summary(false)
            .fromInclusive(true)
            .build();

    private static final Table throughputTable = ImmutableTable.builder()
            .partialName("throughput")
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .summary(false)
            .fromInclusive(true)
            .build();

    private static final Table mainThreadProfileTable = ImmutableTable.builder()
            .partialName("main_thread_profile")
            .addColumns(ImmutableColumn.of("main_thread_profile", "blob"))
            .summary(false)
            .fromInclusive(false)
            .build();

    private static final Table auxThreadProfileTable = ImmutableTable.builder()
            .partialName("aux_thread_profile")
            .addColumns(ImmutableColumn.of("aux_thread_profile", "blob"))
            .summary(false)
            .fromInclusive(false)
            .build();

    private static final Table queriesTable = ImmutableTable.builder()
            .partialName("queries")
            .addColumns(ImmutableColumn.of("queries", "blob"))
            .summary(false)
            .fromInclusive(false)
            .build();

    private final Session session;
    private final AgentDao agentDao;
    private final TransactionTypeDao transactionTypeDao;
    private final ConfigRepository configRepository;

    // list index is rollupLevel
    private final Map<Table, List<PreparedStatement>> insertOverallPS;
    private final Map<Table, List<PreparedStatement>> insertTransactionPS;
    private final Map<Table, List<PreparedStatement>> readOverallPS;
    private final Map<Table, List<PreparedStatement>> readOverallForRollupPS;
    private final Map<Table, List<PreparedStatement>> readTransactionPS;
    private final Map<Table, List<PreparedStatement>> readTransactionForRollupPS;

    private final List<PreparedStatement> existsAuxThreadProfileOverallPS;
    private final List<PreparedStatement> existsAuxThreadProfileTransactionPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    private final ImmutableList<Table> allTables;

    private final AtomicBoolean rollup = new AtomicBoolean();

    public AggregateDao(Session session, AgentDao agentDao, TransactionTypeDao transactionTypeDao,
            ConfigRepository configRepository) {
        this.session = session;
        this.agentDao = agentDao;
        this.transactionTypeDao = transactionTypeDao;
        this.configRepository = configRepository;

        int count = configRepository.getRollupConfigs().size();

        allTables = ImmutableList.of(summaryTable, errorSummaryTable, overviewTable,
                histogramTable, throughputTable, mainThreadProfileTable, auxThreadProfileTable,
                queriesTable);
        Map<Table, List<PreparedStatement>> insertOverallMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> insertTransactionMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> readOverallMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> readOverallForRollupMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> readTransactionMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> readTransactionForRollupMap = Maps.newHashMap();
        for (Table table : allTables) {
            List<PreparedStatement> insertOverallList = Lists.newArrayList();
            List<PreparedStatement> insertTransactionList = Lists.newArrayList();
            List<PreparedStatement> readOverallList = Lists.newArrayList();
            List<PreparedStatement> readOverallForRollupList = Lists.newArrayList();
            List<PreparedStatement> readTransactionList = Lists.newArrayList();
            List<PreparedStatement> readTransactionForRollupList = Lists.newArrayList();
            for (int i = 0; i < count; i++) {
                if (table.summary()) {
                    session.execute(createSummaryTablePS(table, false, i));
                    session.execute(createSummaryTablePS(table, true, i));
                    insertOverallList.add(session.prepare(insertSummaryPS(table, false, i)));
                    insertTransactionList.add(session.prepare(insertSummaryPS(table, true, i)));
                    readOverallList.add(session.prepare(readSummaryPS(table, false, i)));
                    readOverallForRollupList
                            .add(session.prepare(readSummaryForRollupPS(table, false, i)));
                    readTransactionList.add(session.prepare(readSummaryPS(table, true, i)));
                    readTransactionForRollupList
                            .add(session.prepare(readSummaryForRollupPS(table, true, i)));
                } else {
                    session.execute(createTablePS(table, false, i));
                    session.execute(createTablePS(table, true, i));
                    insertOverallList.add(session.prepare(insertPS(table, false, i)));
                    insertTransactionList.add(session.prepare(insertPS(table, true, i)));
                    readOverallList.add(session.prepare(readPS(table, false, i)));
                    readOverallForRollupList.add(session.prepare(readForRollupPS(table, false, i)));
                    readTransactionList.add(session.prepare(readPS(table, true, i)));
                    readTransactionForRollupList
                            .add(session.prepare(readForRollupPS(table, true, i)));
                }
            }
            insertOverallMap.put(table, ImmutableList.copyOf(insertOverallList));
            insertTransactionMap.put(table, ImmutableList.copyOf(insertTransactionList));
            readOverallMap.put(table, ImmutableList.copyOf(readOverallList));
            readOverallForRollupMap.put(table, ImmutableList.copyOf(readOverallForRollupList));
            readTransactionMap.put(table, ImmutableList.copyOf(readTransactionList));
            readTransactionForRollupMap.put(table,
                    ImmutableList.copyOf(readTransactionForRollupList));
        }
        this.insertOverallPS = ImmutableMap.copyOf(insertOverallMap);
        this.insertTransactionPS = ImmutableMap.copyOf(insertTransactionMap);
        this.readOverallPS = ImmutableMap.copyOf(readOverallMap);
        this.readOverallForRollupPS = ImmutableMap.copyOf(readOverallForRollupMap);
        this.readTransactionPS = ImmutableMap.copyOf(readTransactionMap);
        this.readTransactionForRollupPS = ImmutableMap.copyOf(readTransactionForRollupMap);

        List<PreparedStatement> existsAuxThreadProfileOverallPS = Lists.newArrayList();
        List<PreparedStatement> existsAuxThreadProfileTransactionPS = Lists.newArrayList();
        for (int i = 0; i < count; i++) {
            existsAuxThreadProfileOverallPS
                    .add(session.prepare(existsPS(auxThreadProfileTable, false, i)));
            existsAuxThreadProfileTransactionPS
                    .add(session.prepare(existsPS(auxThreadProfileTable, true, i)));
        }
        this.existsAuxThreadProfileOverallPS = existsAuxThreadProfileOverallPS;
        this.existsAuxThreadProfileTransactionPS = existsAuxThreadProfileTransactionPS;

        List<PreparedStatement> insertNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> readNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> deleteNeedsRollup = Lists.newArrayList();
        for (int i = 1; i < count; i++) {
            session.execute("create table if not exists aggregate_needs_rollup_" + i
                    + " (agent_rollup varchar, capture_time timestamp, transaction_type varchar,"
                    + " last_update timeuuid, primary key (agent_rollup, capture_time,"
                    + " transaction_type))");
            insertNeedsRollup.add(session.prepare("insert into aggregate_needs_rollup_" + i
                    + " (agent_rollup, capture_time, transaction_type, last_update) values"
                    + " (?, ?, ?, ?)"));
            readNeedsRollup.add(session.prepare("select capture_time, transaction_type, last_update"
                    + " from aggregate_needs_rollup_" + i + " where agent_rollup = ?"
                    + " and capture_time <= ?"));
            deleteNeedsRollup.add(session.prepare("delete from aggregate_needs_rollup_" + i
                    + " where agent_rollup = ? and capture_time = ? and transaction_type = ?"
                    + " if last_update = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;
    }

    @Override
    public void store(String agentId, long captureTime,
            List<AggregatesByType> aggregatesByTypeList) throws Exception {
        for (AggregatesByType aggregatesByType : aggregatesByTypeList) {
            String transactionType = aggregatesByType.getTransactionType();
            Aggregate overallAggregate = aggregatesByType.getOverallAggregate();
            storeOverallAggregate(0, agentId, transactionType, captureTime, overallAggregate);
            for (TransactionAggregate transactionAggregate : aggregatesByType
                    .getTransactionAggregateList()) {
                storeTransactionAggregate(0, agentId, transactionType,
                        transactionAggregate.getTransactionName(), captureTime,
                        transactionAggregate.getAggregate());
            }
            transactionTypeDao.updateLastCaptureTime(agentId, transactionType);
        }
        BatchStatement batchStatement = new BatchStatement();
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (AggregatesByType aggregatesByType : aggregatesByTypeList) {
            for (int i = 1; i < rollupConfigs.size(); i++) {
                long intervalMillis = rollupConfigs.get(i).intervalMillis();
                long rollupCaptureTime =
                        (long) Math.ceil(captureTime / (double) intervalMillis) * intervalMillis;
                BoundStatement boundStatement = insertNeedsRollup.get(i - 1).bind();
                boundStatement.setString(0, agentId);
                boundStatement.setTimestamp(1, new Date(rollupCaptureTime));
                boundStatement.setString(2, aggregatesByType.getTransactionType());
                boundStatement.setUUID(3, UUIDs.timeBased());
                batchStatement.add(boundStatement);
            }
        }
        session.execute(batchStatement);
        agentDao.updateLastCaptureTime(agentId, true);
        if (!rollup.getAndSet(true)) {
            try {
                rollup(captureTime - 60000);
            } finally {
                rollup.set(false);
            }
        }
    }

    // query.from() is non-inclusive
    @Override
    public OverallSummary readOverallSummary(OverallQuery query) {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        ResultSet results = createBoundStatement(summaryTable, query);
        long lastCaptureTime = 0;
        double totalDurationNanos = 0;
        long transactionCount = 0;
        for (Row row : results) {
            // results are ordered by capture time so Math.max() is not needed here
            lastCaptureTime = checkNotNull(row.getTimestamp(0)).getTime();
            totalDurationNanos += row.getDouble(1);
            transactionCount += row.getLong(2);
        }
        return ImmutableOverallSummary.builder()
                .totalDurationNanos(totalDurationNanos)
                .transactionCount(transactionCount)
                .lastCaptureTime(lastCaptureTime)
                .build();
    }

    // sortOrder and limit are only used by fat agent H2 repository, while the glowroot server
    // repository which currently has to pull in all records anyways, just delegates ordering and
    // limit to TransactionSummaryCollector
    //
    // query.from() is non-inclusive
    @Override
    public void mergeInTransactionSummaries(TransactionSummaryCollector mergedTransactionSummaries,
            OverallQuery query, SummarySortOrder sortOrder, int limit) {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement =
                checkNotNull(readTransactionPS.get(summaryTable)).get(query.rollupLevel()).bind();
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            String transactionName = checkNotNull(row.getString(1));
            double totalDurationNanos = row.getDouble(2);
            long transactionCount = row.getLong(3);
            mergedTransactionSummaries.collect(transactionName, totalDurationNanos,
                    transactionCount, captureTime);
        }
    }

    // query.from() is non-inclusive
    @Override
    public OverallErrorSummary readOverallErrorSummary(OverallQuery query) {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        ResultSet results = createBoundStatement(errorSummaryTable, query);
        long lastCaptureTime = 0;
        long errorCount = 0;
        long transactionCount = 0;
        for (Row row : results) {
            // results are ordered by capture time so Math.max() is not needed here
            lastCaptureTime = checkNotNull(row.getTimestamp(0)).getTime();
            errorCount += row.getLong(1);
            transactionCount += row.getLong(2);
        }
        return ImmutableOverallErrorSummary.builder()
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .lastCaptureTime(lastCaptureTime)
                .build();
    }

    // sortOrder and limit are only used by fat agent H2 repository, while the glowroot server
    // repository which currently has to pull in all records anyways, just delegates ordering and
    // limit to TransactionErrorSummaryCollector
    //
    // query.from() is non-inclusive
    @Override
    public void mergeInTransactionErrorSummaries(
            TransactionErrorSummaryCollector mergedTransactionErrorSummaries, OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit) {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement = checkNotNull(readTransactionPS.get(errorSummaryTable))
                .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            String transactionName = checkNotNull(row.getString(1));
            long errorCount = row.getLong(2);
            long transactionCount = row.getLong(3);
            mergedTransactionErrorSummaries.collect(transactionName, errorCount, transactionCount,
                    captureTime);
        }
    }

    // query.from() is INCLUSIVE
    @Override
    public List<OverviewAggregate> readOverviewAggregates(TransactionQuery query)
            throws IOException {
        ResultSet results = executeQuery(overviewTable, query);
        List<OverviewAggregate> overviewAggregates = Lists.newArrayList();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            List<Aggregate.Timer> mainThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            List<Aggregate.Timer> auxThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            List<Aggregate.Timer> asyncRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                    .captureTime(captureTime)
                    .totalDurationNanos(totalDurationNanos)
                    .transactionCount(transactionCount)
                    .addAllMainThreadRootTimers(mainThreadRootTimers)
                    .addAllAuxThreadRootTimers(auxThreadRootTimers)
                    .addAllAsyncRootTimers(asyncRootTimers);
            ByteBuffer mainThreadStats = row.getBytes(i++);
            if (mainThreadStats != null) {
                builder.mainThreadStats(
                        Aggregate.ThreadStats.parseFrom(ByteString.copyFrom(mainThreadStats)));
            }
            ByteBuffer auxThreadStats = row.getBytes(i++);
            if (auxThreadStats != null) {
                builder.auxThreadStats(
                        Aggregate.ThreadStats.parseFrom(ByteString.copyFrom(auxThreadStats)));
            }
            overviewAggregates.add(builder.build());
        }
        return overviewAggregates;
    }

    // query.from() is INCLUSIVE
    @Override
    public List<PercentileAggregate> readPercentileAggregates(TransactionQuery query)
            throws InvalidProtocolBufferException {
        ResultSet results = executeQuery(histogramTable, query);
        List<PercentileAggregate> percentileAggregates = Lists.newArrayList();
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            double totalDurationNanos = row.getDouble(1);
            long transactionCount = row.getLong(2);
            ByteBuffer bytes = checkNotNull(row.getBytes(3));
            Aggregate.Histogram durationNanosHistogram =
                    Aggregate.Histogram.parseFrom(ByteString.copyFrom(bytes));
            percentileAggregates.add(ImmutablePercentileAggregate.builder()
                    .captureTime(captureTime)
                    .totalDurationNanos(totalDurationNanos)
                    .transactionCount(transactionCount)
                    .durationNanosHistogram(durationNanosHistogram)
                    .build());
        }
        return percentileAggregates;
    }

    // query.from() is INCLUSIVE
    @Override
    public List<ThroughputAggregate> readThroughputAggregates(TransactionQuery query)
            throws IOException {
        ResultSet results = executeQuery(throughputTable, query);
        List<ThroughputAggregate> throughputAggregates = Lists.newArrayList();
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            long transactionCount = row.getLong(1);
            throughputAggregates.add(ImmutableThroughputAggregate.builder()
                    .captureTime(captureTime)
                    .transactionCount(transactionCount)
                    .build());
        }
        return throughputAggregates;
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInMainThreadProfiles(ProfileCollector mergedProfile, TransactionQuery query)
            throws InvalidProtocolBufferException {
        mergeInProfiles(mergedProfile, query, mainThreadProfileTable);
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInAuxThreadProfiles(ProfileCollector mergedProfile, TransactionQuery query)
            throws InvalidProtocolBufferException {
        mergeInProfiles(mergedProfile, query, auxThreadProfileTable);
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInQueries(QueryCollector mergedQueries, TransactionQuery query)
            throws IOException {
        ResultSet results = executeQuery(queriesTable, query);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(0)).getTime());
            ByteBuffer byteBuf = checkNotNull(row.getBytes(1));
            try (InputStream input = new ByteBufferInputStream(byteBuf)) {
                Parser<QueriesByType> parser = Aggregate.QueriesByType.parser();
                QueriesByType message;
                while ((message = parser.parseDelimitedFrom(input)) != null) {
                    mergedQueries.mergeQueries(message);
                    mergedQueries.updateLastCaptureTime(captureTime);
                }
            }
        }
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasAuxThreadProfile(TransactionQuery query) throws Exception {
        BoundStatement boundStatement = query.transactionName() == null
                ? existsAuxThreadProfileOverallPS.get(query.rollupLevel()).bind()
                : existsAuxThreadProfileTransactionPS.get(query.rollupLevel()).bind();
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        return results.one() != null;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveMainThreadProfile(TransactionQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveAuxThreadProfile(TransactionQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveQueries(TransactionQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    @Override
    public void deleteAll(String agentRollup) {
        // this is not currently supported (to avoid row key range query)
        throw new UnsupportedOperationException();
    }

    @OnlyUsedByTests
    void truncateAll() {
        for (Table table : allTables) {
            for (int i = 0; i < configRepository.getRollupConfigs().size(); i++) {
                session.execute("truncate " + getTableName(table.partialName(), false, i));
                session.execute("truncate " + getTableName(table.partialName(), true, i));
            }
        }
        for (int i = 1; i < configRepository.getRollupConfigs().size(); i++) {
            session.execute("truncate aggregate_needs_rollup_" + castUntainted(i));
        }
    }

    // should be called one minute after to reduce likelihood of data coming in late
    void rollup(long sortOfSafeRollupTime) throws Exception {
        for (AgentRollup agentRollup : agentDao.readAgentRollups()) {
            for (int i = 1; i < configRepository.getRollupConfigs().size(); i++) {
                long intervalMillis = configRepository.getRollupConfigs().get(i).intervalMillis();
                rollup(i, agentRollup.name(),
                        (sortOfSafeRollupTime / intervalMillis) * intervalMillis);
            }
        }
    }

    private void rollup(int rollupLevel, String agentRollup, long sortOfSafeRollupTime)
            throws Exception {
        long rollupIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        BoundStatement boundStatement = readNeedsRollup.get(rollupLevel - 1).bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setTimestamp(1, new Date(sortOfSafeRollupTime));
        ResultSet results = session.execute(boundStatement);
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            String transactionType = checkNotNull(row.getString(1));
            UUID lastUpdate = row.getUUID(2);
            rollupOne(rollupLevel, agentRollup, transactionType,
                    captureTime - rollupIntervalMillis, captureTime);
            boundStatement = deleteNeedsRollup.get(rollupLevel - 1).bind();
            boundStatement.setString(0, agentRollup);
            boundStatement.setTimestamp(1, new Date(captureTime));
            boundStatement.setString(2, transactionType);
            boundStatement.setUUID(3, lastUpdate);
            session.execute(boundStatement);
        }
    }

    private void rollupOne(int rollupLevel, String agentRollup, String transactionType, long from,
            long to) throws Exception {
        ScratchBuffer scratchBuffer = new ScratchBuffer();
        TransactionQuery query = ImmutableTransactionQuery.builder()
                .agentRollup(agentRollup)
                .transactionType(transactionType)
                .from(from)
                .to(to)
                .rollupLevel(rollupLevel - 1)
                .build();
        rollupOverallSummary(rollupLevel, query);
        rollupErrorSummary(rollupLevel, query);
        rollupOverview(rollupLevel, query);
        rollupHistogram(rollupLevel, query, scratchBuffer);
        rollupThroughput(rollupLevel, query);
        rollupThreadProfile(rollupLevel, query, mainThreadProfileTable);
        rollupThreadProfile(rollupLevel, query, auxThreadProfileTable);
        rollupQueries(rollupLevel, query);

        List<String> transactionNames = rollupTransactionSummary(rollupLevel, query);
        rollupTransactionErrorSummary(rollupLevel, query);
        for (String transactionName : transactionNames) {
            query = ImmutableTransactionQuery.builder()
                    .agentRollup(agentRollup)
                    .transactionType(transactionType)
                    .transactionName(transactionName)
                    .from(from)
                    .to(to)
                    .rollupLevel(rollupLevel - 1)
                    .build();
            rollupOverview(rollupLevel, query);
            rollupHistogram(rollupLevel, query, scratchBuffer);
            rollupThroughput(rollupLevel, query);
            rollupThreadProfile(rollupLevel, query, mainThreadProfileTable);
            rollupThreadProfile(rollupLevel, query, auxThreadProfileTable);
            rollupQueries(rollupLevel, query);
        }
    }

    private void rollupOverallSummary(int rollupLevel, TransactionQuery query) {
        ResultSet results = executeQueryForRollup(summaryTable, query);
        if (results.isExhausted()) {
            // this probably shouldn't happen
            return;
        }
        double totalDurationNanos = 0;
        long transactionCount = 0;
        for (Row row : results) {
            totalDurationNanos += row.getDouble(0);
            transactionCount += row.getLong(1);
        }
        BoundStatement boundStatement = getInsertOverallPS(summaryTable, rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, query.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, transactionCount);
        session.execute(boundStatement);
    }

    private void rollupErrorSummary(int rollupLevel, TransactionQuery query) {
        ResultSet results = executeQueryForRollup(errorSummaryTable, query);
        if (results.isExhausted()) {
            return;
        }
        long errorCount = 0;
        long transactionCount = 0;
        for (Row row : results) {
            errorCount += row.getLong(0);
            transactionCount += row.getLong(1);
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(errorSummaryTable, rollupLevel).bind();
        } else {
            boundStatement = getInsertTransactionPS(errorSummaryTable, rollupLevel).bind();
        }
        int i = 0;
        boundStatement.setString(i++, query.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.to()));
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setLong(i++, errorCount);
        boundStatement.setLong(i++, transactionCount);
        session.execute(boundStatement);
    }

    private void rollupOverview(int rollupLevel, TransactionQuery query) throws IOException {
        ResultSet results = executeQueryForRollup(overviewTable, query);
        if (results.isExhausted()) {
            // this probably shouldn't happen
            return;
        }
        double totalDurationNanos = 0;
        long transactionCount = 0;
        List<MutableTimer> mainThreadRootTimers = Lists.newArrayList();
        List<MutableTimer> auxThreadRootTimers = Lists.newArrayList();
        List<MutableTimer> asyncRootTimers = Lists.newArrayList();
        MutableThreadStats mainThreadStats = new MutableThreadStats();
        MutableThreadStats auxThreadStats = new MutableThreadStats();
        for (Row row : results) {
            int i = 0;
            totalDurationNanos += row.getDouble(i++);
            transactionCount += row.getLong(i++);
            List<Aggregate.Timer> toBeMergedMainThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            MutableAggregate.mergeRootTimers(toBeMergedMainThreadRootTimers, mainThreadRootTimers);
            List<Aggregate.Timer> toBeMergedAuxThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            MutableAggregate.mergeRootTimers(toBeMergedAuxThreadRootTimers, auxThreadRootTimers);
            List<Aggregate.Timer> toBeMergedAsyncRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            MutableAggregate.mergeRootTimers(toBeMergedAsyncRootTimers, asyncRootTimers);
            ByteBuffer toBeMergedMainThreadStats = row.getBytes(i++);
            if (toBeMergedMainThreadStats != null) {
                mainThreadStats.addThreadStats(Aggregate.ThreadStats
                        .parseFrom(ByteString.copyFrom(toBeMergedMainThreadStats)));
            }
            ByteBuffer toBeMergedAuxThreadStats = row.getBytes(i++);
            if (toBeMergedAuxThreadStats != null) {
                auxThreadStats.addThreadStats(Aggregate.ThreadStats
                        .parseFrom(ByteString.copyFrom(toBeMergedAuxThreadStats)));
            }
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(overviewTable, rollupLevel).bind();
        } else {
            boundStatement = getInsertTransactionPS(overviewTable, rollupLevel).bind();
        }
        int i = 0;
        boundStatement.setString(i++, query.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setBytes(i++,
                Messages.toByteBuffer(MutableAggregate.toProto(mainThreadRootTimers)));
        boundStatement.setBytes(i++,
                Messages.toByteBuffer(MutableAggregate.toProto(auxThreadRootTimers)));
        boundStatement.setBytes(i++,
                Messages.toByteBuffer(MutableAggregate.toProto(asyncRootTimers)));
        boundStatement.setBytes(i++, toByteBuffer(mainThreadStats.toProto()));
        boundStatement.setBytes(i++, toByteBuffer(auxThreadStats.toProto()));
        session.execute(boundStatement);
    }

    private void rollupHistogram(int rollupLevel, TransactionQuery query,
            ScratchBuffer scratchBuffer) throws Exception {
        ResultSet results = executeQueryForRollup(histogramTable, query);
        if (results.isExhausted()) {
            // this probably shouldn't happen
            return;
        }
        double totalDurationNanos = 0;
        long transactionCount = 0;
        LazyHistogram durationNanosHistogram = new LazyHistogram();
        for (Row row : results) {
            totalDurationNanos += row.getDouble(0);
            transactionCount += row.getLong(1);
            ByteBuffer bytes = checkNotNull(row.getBytes(2));
            durationNanosHistogram.merge(Aggregate.Histogram.parseFrom(ByteString.copyFrom(bytes)));
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(histogramTable, rollupLevel).bind();
        } else {
            boundStatement = getInsertTransactionPS(histogramTable, rollupLevel).bind();
        }
        int i = 0;
        boundStatement.setString(i++, query.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setBytes(i++, toByteBuffer(durationNanosHistogram.toProto(scratchBuffer)));
        session.execute(boundStatement);
    }

    private void rollupThroughput(int rollupLevel, TransactionQuery query) {
        ResultSet results = executeQueryForRollup(throughputTable, query);
        if (results.isExhausted()) {
            // this probably shouldn't happen
            return;
        }
        long transactionCount = 0;
        for (Row row : results) {
            transactionCount += row.getLong(0);
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(throughputTable, rollupLevel).bind();
        } else {
            boundStatement = getInsertTransactionPS(throughputTable, rollupLevel).bind();
        }
        int i = 0;
        boundStatement.setString(i++, query.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setLong(i++, transactionCount);
        session.execute(boundStatement);
    }

    private void rollupThreadProfile(int rollupLevel, TransactionQuery query, Table table)
            throws InvalidProtocolBufferException {
        ResultSet results = executeQueryForRollup(table, query);
        if (results.isExhausted()) {
            return;
        }
        MutableProfile profile = new MutableProfile();
        for (Row row : results) {
            ByteBuffer bytes = checkNotNull(row.getBytes(0));
            profile.merge(Profile.parseFrom(ByteString.copyFrom(bytes)));
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(table, rollupLevel).bind();
        } else {
            boundStatement = getInsertTransactionPS(table, rollupLevel).bind();
        }
        int i = 0;
        boundStatement.setString(i++, query.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setBytes(i++, toByteBuffer(profile.toProto()));
        session.execute(boundStatement);
    }

    private void rollupQueries(int rollupLevel, TransactionQuery query) throws IOException {
        ResultSet results = executeQueryForRollup(queriesTable, query);
        if (results.isExhausted()) {
            return;
        }
        QueryCollector queryCollector =
                new QueryCollector(getMaxAggregateQueriesPerQueryType(query.agentRollup()), 0);
        for (Row row : results) {
            ByteBuffer bytes = checkNotNull(row.getBytes(0));
            queryCollector.mergeQueries(
                    Messages.parseDelimitedFrom(bytes, Aggregate.QueriesByType.parser()));
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(queriesTable, rollupLevel).bind();
        } else {
            boundStatement = getInsertTransactionPS(queriesTable, rollupLevel).bind();
        }
        int i = 0;
        boundStatement.setString(i++, query.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setBytes(i++, Messages.toByteBuffer(queryCollector.toProto()));
        session.execute(boundStatement);
    }

    private List<String> rollupTransactionSummary(int rollupLevel, TransactionQuery query) {
        BoundStatement boundStatement = checkNotNull(readTransactionForRollupPS.get(summaryTable))
                .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        if (results.isExhausted()) {
            // this probably shouldn't happen
            return ImmutableList.of();
        }
        Map<String, MutableSummary> summaries = Maps.newHashMap();
        for (Row row : results) {
            String transactionName = checkNotNull(row.getString(0));
            MutableSummary summary = summaries.get(transactionName);
            if (summary == null) {
                summary = new MutableSummary();
                summaries.put(transactionName, summary);
            }
            summary.totalDurationNanos += row.getDouble(1);
            summary.transactionCount += row.getLong(2);
        }
        PreparedStatement preparedStatement = getInsertTransactionPS(summaryTable, rollupLevel);
        for (Entry<String, MutableSummary> entry : summaries.entrySet()) {
            MutableSummary summary = entry.getValue();
            boundStatement = preparedStatement.bind();
            int i = 0;
            boundStatement.setString(i++, query.agentRollup());
            boundStatement.setString(i++, query.transactionType());
            boundStatement.setTimestamp(i++, new Date(query.to()));
            boundStatement.setString(i++, entry.getKey());
            boundStatement.setDouble(i++, summary.totalDurationNanos);
            boundStatement.setLong(i++, summary.transactionCount);
            session.execute(boundStatement);
        }
        return ImmutableList.copyOf(summaries.keySet());
    }

    private void rollupTransactionErrorSummary(int rollupLevel, TransactionQuery query) {
        BoundStatement boundStatement =
                checkNotNull(readTransactionForRollupPS.get(errorSummaryTable))
                        .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        if (results.isExhausted()) {
            return;
        }
        Map<String, MutableErrorSummary> summaries = Maps.newHashMap();
        for (Row row : results) {
            String transactionName = checkNotNull(row.getString(0));
            MutableErrorSummary summary = summaries.get(transactionName);
            if (summary == null) {
                summary = new MutableErrorSummary();
                summaries.put(transactionName, summary);
            }
            summary.errorCount += row.getLong(1);
            summary.transactionCount += row.getLong(2);
        }
        PreparedStatement preparedStatement =
                getInsertTransactionPS(errorSummaryTable, rollupLevel);
        for (Entry<String, MutableErrorSummary> entry : summaries.entrySet()) {
            MutableErrorSummary summary = entry.getValue();
            boundStatement = preparedStatement.bind();
            int i = 0;
            boundStatement.setString(i++, query.agentRollup());
            boundStatement.setString(i++, query.transactionType());
            boundStatement.setTimestamp(i++, new Date(query.to()));
            boundStatement.setString(i++, entry.getKey());
            boundStatement.setLong(i++, summary.errorCount);
            boundStatement.setLong(i++, summary.transactionCount);
            session.execute(boundStatement);
        }
    }

    private void storeOverallAggregate(int rollupLevel, String agentRollup, String transactionType,
            long captureTime, Aggregate aggregate) throws IOException {

        BoundStatement boundStatement = getInsertOverallPS(summaryTable, rollupLevel).bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setTimestamp(2, new Date(captureTime));
        boundStatement.setDouble(3, aggregate.getTotalDurationNanos());
        boundStatement.setLong(4, aggregate.getTransactionCount());
        session.execute(boundStatement);

        if (aggregate.getErrorCount() > 0) {
            boundStatement = getInsertOverallPS(errorSummaryTable, rollupLevel).bind();
            boundStatement.setString(0, agentRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setTimestamp(2, new Date(captureTime));
            boundStatement.setLong(3, aggregate.getErrorCount());
            boundStatement.setLong(4, aggregate.getTransactionCount());
            session.execute(boundStatement);
        }

        boundStatement = getInsertOverallPS(overviewTable, rollupLevel).bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setTimestamp(2, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, 3);
        session.execute(boundStatement);

        boundStatement = getInsertOverallPS(histogramTable, rollupLevel).bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setTimestamp(2, new Date(captureTime));
        boundStatement.setDouble(3, aggregate.getTotalDurationNanos());
        boundStatement.setLong(4, aggregate.getTransactionCount());
        boundStatement.setBytes(5, toByteBuffer(aggregate.getDurationNanosHistogram()));
        session.execute(boundStatement);

        boundStatement = getInsertOverallPS(throughputTable, rollupLevel).bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setTimestamp(2, new Date(captureTime));
        boundStatement.setLong(3, aggregate.getTransactionCount());
        session.execute(boundStatement);

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            boundStatement = getInsertOverallPS(mainThreadProfileTable, rollupLevel).bind();
            boundStatement.setString(0, agentRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setTimestamp(2, new Date(captureTime));
            boundStatement.setBytes(3, toByteBuffer(profile));
            session.execute(boundStatement);
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            boundStatement = getInsertOverallPS(auxThreadProfileTable, rollupLevel).bind();
            boundStatement.setString(0, agentRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setTimestamp(2, new Date(captureTime));
            boundStatement.setBytes(3, toByteBuffer(profile));
            session.execute(boundStatement);
        }
        List<QueriesByType> queriesByTypeList = aggregate.getQueriesByTypeList();
        if (!queriesByTypeList.isEmpty()) {
            boundStatement = getInsertOverallPS(queriesTable, rollupLevel).bind();
            boundStatement.setString(0, agentRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setTimestamp(2, new Date(captureTime));
            boundStatement.setBytes(3, Messages.toByteBuffer(queriesByTypeList));
            session.execute(boundStatement);
        }
    }

    private void storeTransactionAggregate(int rollupLevel, String agentRollup,
            String transactionType, String transactionName, long captureTime, Aggregate aggregate)
                    throws IOException {

        BoundStatement boundStatement = getInsertTransactionPS(summaryTable, rollupLevel).bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setTimestamp(2, new Date(captureTime));
        boundStatement.setString(3, transactionName);
        boundStatement.setDouble(4, aggregate.getTotalDurationNanos());
        boundStatement.setLong(5, aggregate.getTransactionCount());
        session.execute(boundStatement);

        if (aggregate.getErrorCount() > 0) {
            boundStatement = getInsertTransactionPS(errorSummaryTable, rollupLevel).bind();
            boundStatement.setString(0, agentRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setTimestamp(2, new Date(captureTime));
            boundStatement.setString(3, transactionName);
            boundStatement.setLong(4, aggregate.getErrorCount());
            boundStatement.setLong(5, aggregate.getTransactionCount());
            session.execute(boundStatement);
        }

        boundStatement = getInsertTransactionPS(overviewTable, rollupLevel).bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setString(2, transactionName);
        boundStatement.setTimestamp(3, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, 4);
        session.execute(boundStatement);

        boundStatement = getInsertTransactionPS(histogramTable, rollupLevel).bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setString(2, transactionName);
        boundStatement.setTimestamp(3, new Date(captureTime));
        boundStatement.setDouble(4, aggregate.getTotalDurationNanos());
        boundStatement.setLong(5, aggregate.getTransactionCount());
        boundStatement.setBytes(6, toByteBuffer(aggregate.getDurationNanosHistogram()));
        session.execute(boundStatement);

        boundStatement = getInsertTransactionPS(throughputTable, rollupLevel).bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setString(2, transactionName);
        boundStatement.setTimestamp(3, new Date(captureTime));
        boundStatement.setLong(4, aggregate.getTransactionCount());
        session.execute(boundStatement);

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            boundStatement = getInsertTransactionPS(mainThreadProfileTable, rollupLevel).bind();
            boundStatement.setString(0, agentRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setString(2, transactionName);
            boundStatement.setTimestamp(3, new Date(captureTime));
            boundStatement.setBytes(4, toByteBuffer(profile));
            session.execute(boundStatement);
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            boundStatement = getInsertTransactionPS(auxThreadProfileTable, rollupLevel).bind();
            boundStatement.setString(0, agentRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setString(2, transactionName);
            boundStatement.setTimestamp(3, new Date(captureTime));
            boundStatement.setBytes(4, toByteBuffer(profile));
            session.execute(boundStatement);
        }
        List<QueriesByType> queriesByTypeList = aggregate.getQueriesByTypeList();
        if (!queriesByTypeList.isEmpty()) {
            boundStatement = getInsertTransactionPS(queriesTable, rollupLevel).bind();
            boundStatement.setString(0, agentRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setString(2, transactionName);
            boundStatement.setTimestamp(3, new Date(captureTime));
            boundStatement.setBytes(4, Messages.toByteBuffer(queriesByTypeList));
            session.execute(boundStatement);
        }
    }

    private PreparedStatement getInsertOverallPS(Table table, int rollupLevel) {
        return checkNotNull(insertOverallPS.get(table)).get(rollupLevel);
    }

    private PreparedStatement getInsertTransactionPS(Table table, int rollupLevel) {
        return checkNotNull(insertTransactionPS.get(table)).get(rollupLevel);
    }

    private void bindAggregate(BoundStatement boundStatement, Aggregate aggregate, int startIndex)
            throws IOException {
        int i = startIndex;
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        List<Timer> mainThreadRootTimers = aggregate.getMainThreadRootTimerList();
        if (!mainThreadRootTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(mainThreadRootTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        List<Timer> auxThreadRootTimers = aggregate.getAuxThreadRootTimerList();
        if (!auxThreadRootTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(auxThreadRootTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        List<Timer> asyncRootTimers = aggregate.getAsyncRootTimerList();
        if (!asyncRootTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(asyncRootTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        if (aggregate.hasMainThreadStats()) {
            boundStatement.setBytes(i++, toByteBuffer(aggregate.getMainThreadStats()));
        } else {
            boundStatement.setToNull(i++);
        }
        if (aggregate.hasAuxThreadStats()) {
            boundStatement.setBytes(i++, toByteBuffer(aggregate.getAuxThreadStats()));
        } else {
            boundStatement.setToNull(i++);
        }
    }

    private ResultSet createBoundStatement(Table table, OverallQuery query) {
        BoundStatement boundStatement =
                checkNotNull(readOverallPS.get(table)).get(query.rollupLevel()).bind();
        bindQuery(boundStatement, query);
        return session.execute(boundStatement);
    }

    private ResultSet executeQuery(Table table, TransactionQuery query) {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = checkNotNull(readOverallPS.get(table)).get(query.rollupLevel()).bind();
        } else {
            boundStatement =
                    checkNotNull(readTransactionPS.get(table)).get(query.rollupLevel()).bind();
        }
        bindQuery(boundStatement, query);
        return session.execute(boundStatement);
    }

    private ResultSet executeQueryForRollup(Table table, TransactionQuery query) {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement =
                    checkNotNull(readOverallForRollupPS.get(table)).get(query.rollupLevel()).bind();
        } else {
            boundStatement = checkNotNull(readTransactionForRollupPS.get(table))
                    .get(query.rollupLevel()).bind();
        }
        bindQuery(boundStatement, query);
        return session.execute(boundStatement);
    }

    private void mergeInProfiles(ProfileCollector mergedProfile, TransactionQuery query,
            Table profileTable) throws InvalidProtocolBufferException {
        ResultSet results = executeQuery(profileTable, query);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(0)).getTime());
            ByteBuffer bytes = checkNotNull(row.getBytes(1));
            // TODO optimize this byte copying
            Profile profile = Profile.parseFrom(ByteString.copyFrom(bytes));
            mergedProfile.mergeProfile(profile);
            mergedProfile.updateLastCaptureTime(captureTime);
        }
    }

    private int getMaxAggregateQueriesPerQueryType(String agentId) throws IOException {
        AdvancedConfig advancedConfig = configRepository.getAdvancedConfig(agentId);
        if (advancedConfig != null && advancedConfig.hasMaxAggregateQueriesPerQueryType()) {
            return advancedConfig.getMaxAggregateQueriesPerQueryType().getValue();
        } else {
            return ConfigDefaults.MAX_AGGREGATE_QUERIES_PER_QUERY_TYPE;
        }
    }

    private static void bindQuery(BoundStatement boundStatement, OverallQuery query) {
        int i = 0;
        boundStatement.setString(i++, query.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.from()));
        boundStatement.setTimestamp(i++, new Date(query.to()));
    }

    private static void bindQuery(BoundStatement boundStatement, TransactionQuery query) {
        int i = 0;
        boundStatement.setString(i++, query.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            boundStatement.setString(i++, transactionName);
        }
        boundStatement.setTimestamp(i++, new Date(query.from()));
        boundStatement.setTimestamp(i++, new Date(query.to()));
    }

    private static String createTablePS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("create table if not exists ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (agent_rollup varchar, transaction_type varchar");
        if (transaction) {
            sb.append(", transaction_name varchar");
        }
        sb.append(", capture_time timestamp");
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
            sb.append(" ");
            sb.append(column.type());
        }
        if (transaction) {
            sb.append(", primary key ((agent_rollup, transaction_type, transaction_name),"
                    + " capture_time))");
        } else {
            sb.append(", primary key ((agent_rollup, transaction_type), capture_time))");
        }
        return sb.toString();
    }

    private static String insertPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("insert into ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (agent_rollup, transaction_type");
        if (transaction) {
            sb.append(", transaction_name");
        }
        sb.append(", capture_time");
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(") values (?, ?, ?");
        if (transaction) {
            sb.append(", ?");
        }
        sb.append(Strings.repeat(", ?", table.columns().size()));
        sb.append(")");
        return sb.toString();
    }

    private static String readPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select capture_time");
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ?");
        if (transaction) {
            sb.append(" and transaction_name = ?");
        }
        sb.append(" and capture_time >");
        if (table.fromInclusive()) {
            sb.append("=");
        }
        sb.append(" ? and capture_time <= ?");
        return sb.toString();
    }

    private static String readForRollupPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select ");
        boolean addSeparator = false;
        for (Column column : table.columns()) {
            if (addSeparator) {
                sb.append(", ");
            }
            sb.append(column.name());
            addSeparator = true;
        }
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ?");
        if (transaction) {
            sb.append(" and transaction_name = ?");
        }
        sb.append(" and capture_time > ? and capture_time <= ?");
        return sb.toString();
    }

    private static String existsPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select agent_rollup");
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ?");
        if (transaction) {
            sb.append(" and transaction_name = ?");
        }
        sb.append(" and capture_time >");
        if (table.fromInclusive()) {
            sb.append("=");
        }
        sb.append(" ? and capture_time <= ? limit 1");
        return sb.toString();
    }

    private static String createSummaryTablePS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("create table if not exists ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (agent_rollup varchar, transaction_type varchar, capture_time timestamp");
        if (transaction) {
            sb.append(", transaction_name varchar");
        }
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
            sb.append(" ");
            sb.append(column.type());
        }
        if (transaction) {
            sb.append(", primary key ((agent_rollup, transaction_type),"
                    + " capture_time, transaction_name))");
        } else {
            sb.append(", primary key ((agent_rollup, transaction_type), capture_time))");
        }
        return sb.toString();
    }

    private static String insertSummaryPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("insert into ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (agent_rollup, transaction_type, capture_time");
        if (transaction) {
            sb.append(", transaction_name");
        }
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(") values (?, ?, ?");
        if (transaction) {
            sb.append(", ?");
        }
        sb.append(Strings.repeat(", ?", table.columns().size()));
        sb.append(")");
        return sb.toString();
    }

    // currently have to do group by / sort / limit client-side, even on overall_summary
    // because sum(double) requires Cassandra 2.2+
    private static String readSummaryPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        // capture_time is needed to keep track of lastCaptureTime for rollup level when merging
        // recent non-rolled up data
        sb.append("select capture_time");
        if (transaction) {
            sb.append(", transaction_name");
        }
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ? and capture_time >");
        if (table.fromInclusive()) {
            sb.append("=");
        }
        sb.append(" ? and capture_time <= ?");
        return sb.toString();
    }

    private static String readSummaryForRollupPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select ");
        if (transaction) {
            sb.append("transaction_name, ");
        }
        boolean addSeparator = false;
        for (Column column : table.columns()) {
            if (addSeparator) {
                sb.append(", ");
            }
            sb.append(column.name());
            addSeparator = true;
        }
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ? and capture_time >");
        sb.append(" ? and capture_time <= ?");
        return sb.toString();
    }

    private static StringBuilder getTableName(String partialName, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("aggregate_");
        if (transaction) {
            sb.append("tn_");
        } else {
            sb.append("tt_");
        }
        sb.append(partialName);
        sb.append("_rollup_");
        sb.append(i);
        return sb;
    }

    private static ByteBuffer toByteBuffer(AbstractMessage message) {
        return ByteBuffer.wrap(message.toByteString().toByteArray());
    }

    @Value.Immutable
    interface Table {
        String partialName();
        List<String> partitionKey();
        List<String> clusterKey();
        List<Column> columns();
        boolean summary();
        boolean fromInclusive();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface Column {
        String name();
        String type();
    }

    private static class MutableSummary {
        private double totalDurationNanos;
        private long transactionCount;
    }

    private static class MutableErrorSummary {
        private long errorCount;
        private long transactionCount;
    }
}
