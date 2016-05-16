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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import org.immutables.value.Value;

import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.ErrorSummarySortOrder;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.SummarySortOrder;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Styles;
import org.glowroot.server.util.ByteBufferInputStream;
import org.glowroot.server.util.Messages;
import org.glowroot.storage.config.ConfigDefaults;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.MutableAggregate;
import org.glowroot.storage.repo.MutableThreadStats;
import org.glowroot.storage.repo.MutableTimer;
import org.glowroot.storage.repo.Utils;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.QueriesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.ServiceCallsByType;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.Timer;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

public class AggregateDao implements AggregateRepository {

    private static final String WITH_DTCS =
            "with compaction = { 'class' : 'DateTieredCompactionStrategy' }";

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

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
            .addColumns(ImmutableColumn.of("async_transactions", "boolean"))
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

    private static final Table queriesTable = ImmutableTable.builder()
            .partialName("queries")
            .addColumns(ImmutableColumn.of("queries", "blob"))
            .summary(false)
            .fromInclusive(false)
            .build();

    private static final Table serviceCallsTable = ImmutableTable.builder()
            .partialName("service_calls")
            .addColumns(ImmutableColumn.of("service_calls", "blob"))
            .summary(false)
            .fromInclusive(false)
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

    private final Session session;
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

    public AggregateDao(Session session, TransactionTypeDao transactionTypeDao,
            ConfigRepository configRepository) {
        this.session = session;
        this.transactionTypeDao = transactionTypeDao;
        this.configRepository = configRepository;

        int count = configRepository.getRollupConfigs().size();

        allTables = ImmutableList.of(summaryTable, errorSummaryTable, overviewTable,
                histogramTable, throughputTable, queriesTable, serviceCallsTable,
                mainThreadProfileTable, auxThreadProfileTable);
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
                    + " (agent_rollup varchar, capture_time timestamp,"
                    + " transaction_types set<varchar>, last_update timeuuid,"
                    + " primary key (agent_rollup, capture_time)) " + WITH_LCS);
            insertNeedsRollup.add(session.prepare("insert into aggregate_needs_rollup_" + i
                    + " (agent_rollup, capture_time, transaction_types, last_update) values"
                    + " (?, ?, ?, ?)"));
            readNeedsRollup.add(session.prepare("select agent_rollup, capture_time,"
                    + " transaction_types, last_update from aggregate_needs_rollup_" + i));
            deleteNeedsRollup.add(session.prepare("delete from aggregate_needs_rollup_" + i
                    + " where agent_rollup = ? and capture_time = ? if last_update = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;
    }

    @Override
    public void store(String agentId, long captureTime,
            List<AggregatesByType> aggregatesByTypeList) throws Exception {
        List<Integer> ttls = getTTLs();
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (AggregatesByType aggregatesByType : aggregatesByTypeList) {
            String transactionType = aggregatesByType.getTransactionType();
            // TEMPORARY UNTIL ROLL OUT AGENT 0.9.0
            if (transactionType.equals("Servlet")) {
                transactionType = "Web";
            }
            // END TEMPORARY
            Aggregate overallAggregate = aggregatesByType.getOverallAggregate();
            futures.addAll(storeOverallAggregate(agentId, transactionType, captureTime,
                    overallAggregate, ttls.get(0)));
            for (TransactionAggregate transactionAggregate : aggregatesByType
                    .getTransactionAggregateList()) {
                futures.addAll(storeTransactionAggregate(agentId, transactionType,
                        transactionAggregate.getTransactionName(), captureTime,
                        transactionAggregate.getAggregate(), ttls.get(0)));
            }
            transactionTypeDao.maybeUpdateLastCaptureTime(agentId, transactionType, futures);
        }
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (int i = 1; i < rollupConfigs.size(); i++) {
            // TODO report checker framework issue that occurs without this suppression
            @SuppressWarnings("assignment.type.incompatible")
            Set<String> transactionTypes = aggregatesByTypeList.stream()
                    .map(AggregatesByType::getTransactionType).collect(Collectors.toSet());
            // TEMPORARY UNTIL ROLL OUT AGENT 0.9.0
            if (transactionTypes.remove("Servlet")) {
                transactionTypes.add("Web");
            }
            // END TEMPORARY
            long intervalMillis = rollupConfigs.get(i).intervalMillis();
            long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, intervalMillis);
            BoundStatement boundStatement = insertNeedsRollup.get(i - 1).bind();
            boundStatement.setString(0, agentId);
            boundStatement.setTimestamp(1, new Date(rollupCaptureTime));
            boundStatement.setSet(2, transactionTypes);
            boundStatement.setUUID(3, UUIDs.timeBased());
            futures.add(session.executeAsync(boundStatement));
        }
        Futures.allAsList(futures).get();
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInOverallSummary(String agentRollup, OverallQuery query,
            OverallSummaryCollector collector) {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        ResultSet results = createBoundStatement(agentRollup, query, summaryTable);
        for (Row row : results) {
            // results are ordered by capture time so Math.max() is not needed here
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            double totalDurationNanos = row.getDouble(1);
            long transactionCount = row.getLong(2);
            collector.mergeSummary(totalDurationNanos, transactionCount, captureTime);
        }
    }

    // sortOrder and limit are only used by fat agent H2 repository, while the glowroot server
    // repository which currently has to pull in all records anyways, just delegates ordering and
    // limit to TransactionSummaryCollector
    //
    // query.from() is non-inclusive
    @Override
    public void mergeInTransactionSummaries(String agentRollup, OverallQuery query,
            SummarySortOrder sortOrder, int limit, TransactionSummaryCollector collector) {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement =
                checkNotNull(readTransactionPS.get(summaryTable)).get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollup, query);
        ResultSet results = session.execute(boundStatement);
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            String transactionName = checkNotNull(row.getString(1));
            double totalDurationNanos = row.getDouble(2);
            long transactionCount = row.getLong(3);
            collector.collect(transactionName, totalDurationNanos,
                    transactionCount, captureTime);
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInOverallErrorSummary(String agentRollup, OverallQuery query,
            OverallErrorSummaryCollector collector) {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        ResultSet results = createBoundStatement(agentRollup, query, errorSummaryTable);
        for (Row row : results) {
            // results are ordered by capture time so Math.max() is not needed here
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            long errorCount = row.getLong(1);
            long transactionCount = row.getLong(2);
            collector.mergeErrorSummary(errorCount, transactionCount, captureTime);
        }
    }

    // sortOrder and limit are only used by fat agent H2 repository, while the glowroot server
    // repository which currently has to pull in all records anyways, just delegates ordering and
    // limit to TransactionErrorSummaryCollector
    //
    // query.from() is non-inclusive
    @Override
    public void mergeInTransactionErrorSummaries(String agentRollup, OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit,
            TransactionErrorSummaryCollector collector) {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement = checkNotNull(readTransactionPS.get(errorSummaryTable))
                .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollup, query);
        ResultSet results = session.execute(boundStatement);
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            String transactionName = checkNotNull(row.getString(1));
            long errorCount = row.getLong(2);
            long transactionCount = row.getLong(3);
            collector.collect(transactionName, errorCount, transactionCount, captureTime);
        }
    }

    // query.from() is INCLUSIVE
    @Override
    public List<OverviewAggregate> readOverviewAggregates(String agentRollup,
            TransactionQuery query)
            throws IOException {
        ResultSet results = executeQuery(agentRollup, query, overviewTable);
        List<OverviewAggregate> overviewAggregates = Lists.newArrayList();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            boolean asyncTransactions = row.getBool(i++);
            List<Aggregate.Timer> mainThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            List<Aggregate.Timer> auxThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            List<Aggregate.Timer> asyncTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                    .captureTime(captureTime)
                    .totalDurationNanos(totalDurationNanos)
                    .transactionCount(transactionCount)
                    .asyncTransactions(asyncTransactions)
                    .addAllMainThreadRootTimers(mainThreadRootTimers)
                    .addAllAuxThreadRootTimers(auxThreadRootTimers)
                    .addAllAsyncTimers(asyncTimers);
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
    public List<PercentileAggregate> readPercentileAggregates(String agentRollup,
            TransactionQuery query) throws InvalidProtocolBufferException {
        ResultSet results = executeQuery(agentRollup, query, histogramTable);
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
    public List<ThroughputAggregate> readThroughputAggregates(String agentRollup,
            TransactionQuery query) throws IOException {
        ResultSet results = executeQuery(agentRollup, query, throughputTable);
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
    public void mergeInQueries(String agentRollup, TransactionQuery query, QueryCollector collector)
            throws IOException {
        ResultSet results = executeQuery(agentRollup, query, queriesTable);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(0)).getTime());
            ByteBuffer byteBuf = checkNotNull(row.getBytes(1));
            try (InputStream input = new ByteBufferInputStream(byteBuf)) {
                Parser<QueriesByType> parser = Aggregate.QueriesByType.parser();
                QueriesByType message;
                while ((message = parser.parseDelimitedFrom(input)) != null) {
                    collector.mergeQueries(message);
                    collector.updateLastCaptureTime(captureTime);
                }
            }
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInServiceCalls(String agentRollup, TransactionQuery query,
            ServiceCallCollector collector) throws IOException {
        ResultSet results = executeQuery(agentRollup, query, serviceCallsTable);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(0)).getTime());
            ByteBuffer byteBuf = checkNotNull(row.getBytes(1));
            try (InputStream input = new ByteBufferInputStream(byteBuf)) {
                Parser<ServiceCallsByType> parser = Aggregate.ServiceCallsByType.parser();
                ServiceCallsByType message;
                while ((message = parser.parseDelimitedFrom(input)) != null) {
                    collector.mergeQueries(message);
                    collector.updateLastCaptureTime(captureTime);
                }
            }
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInMainThreadProfiles(String agentRollup, TransactionQuery query,
            ProfileCollector collector) throws InvalidProtocolBufferException {
        mergeInProfiles(agentRollup, query, mainThreadProfileTable, collector);
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInAuxThreadProfiles(String agentRollup, TransactionQuery query,
            ProfileCollector collector) throws InvalidProtocolBufferException {
        mergeInProfiles(agentRollup, query, auxThreadProfileTable, collector);
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasAuxThreadProfile(String agentRollup, TransactionQuery query)
            throws Exception {
        BoundStatement boundStatement = query.transactionName() == null
                ? existsAuxThreadProfileOverallPS.get(query.rollupLevel()).bind()
                : existsAuxThreadProfileTransactionPS.get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollup, query);
        ResultSet results = session.execute(boundStatement);
        return results.one() != null;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveQueries(String agentRollup, TransactionQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveServiceCalls(String agentRollup, TransactionQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveMainThreadProfile(String agentRollup, TransactionQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveAuxThreadProfile(String agentRollup, TransactionQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    @Override
    public void deleteAll() {
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
            session.execute("truncate aggregate_needs_rollup_" + i);
        }
    }

    void rollup() throws Exception {
        List<Integer> ttls = getTTLs();
        for (int rollupLevel = 1; rollupLevel < configRepository.getRollupConfigs()
                .size(); rollupLevel++) {
            rollupLevel(rollupLevel, ttls);
        }
    }

    private void rollupLevel(int rollupLevel, List<Integer> ttls)
            throws Exception, InterruptedException, ExecutionException {
        ListMultimap<String, Row> agentRows = getNeedsRollup(rollupLevel);
        long rollupIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        for (String agentRollup : agentRows.keySet()) {
            RollupParams rollupParams =
                    getRollupParams(agentRollup, rollupLevel, ttls.get(rollupLevel));
            for (Row row : agentRows.get(agentRollup)) {
                long captureTime = checkNotNull(row.getTimestamp(1)).getTime();
                Set<String> transactionTypes = row.getSet(2, String.class);
                UUID lastUpdate = row.getUUID(3);
                for (String transactionType : transactionTypes) {
                    rollupOne(rollupParams, transactionType, captureTime - rollupIntervalMillis,
                            captureTime);
                }
                BoundStatement boundStatement = deleteNeedsRollup.get(rollupLevel - 1).bind();
                boundStatement.setString(0, agentRollup);
                boundStatement.setTimestamp(1, new Date(captureTime));
                boundStatement.setUUID(2, lastUpdate);
                session.execute(boundStatement);
            }
        }
    }

    private ListMultimap<String, Row> getNeedsRollup(int rollupLevel) {
        BoundStatement boundStatement = readNeedsRollup.get(rollupLevel - 1).bind();
        ResultSet results = session.execute(boundStatement);
        ListMultimap<String, Row> agentRows = ArrayListMultimap.create();
        for (Row row : results) {
            String agentRollup = checkNotNull(row.getString(0));
            agentRows.put(agentRollup, row);
        }
        // copy of key set is required since removing a key's last remaining value from a multimap
        // removes the key itself which then triggers ConcurrentModificationException
        for (String agentRollup : ImmutableList.copyOf(agentRows.keySet())) {
            List<Row> rows = agentRows.get(agentRollup);
            // don't roll up the most recent one since it is likely still being added, this is
            // mostly to avoid rolling up this data twice, but also currently the UI assumes when it
            // finds a 1-min rollup it doesn't check for non-rolled up 1-min aggregates
            rows.remove(rows.size() - 1);
        }
        return agentRows;
    }

    private void rollupOne(RollupParams rollup, String transactionType, long from, long to)
            throws Exception {
        ScratchBuffer scratchBuffer = new ScratchBuffer();
        TransactionQuery query = ImmutableTransactionQuery.builder()
                .transactionType(transactionType)
                .from(from)
                .to(to)
                .rollupLevel(rollup.rollupLevel() - 1)
                .build();
        List<ResultSetFuture> futures = Lists.newArrayList();
        futures.addAll(rollupOverallSummary(rollup, query));
        futures.addAll(rollupErrorSummary(rollup, query));
        futures.addAll(rollupOverview(rollup, query));
        futures.addAll(rollupHistogram(rollup, query, scratchBuffer));
        futures.addAll(rollupThroughput(rollup, query));
        futures.addAll(rollupQueries(rollup, query));
        futures.addAll(rollupServiceCalls(rollup, query));
        futures.addAll(rollupThreadProfile(rollup, query, mainThreadProfileTable));
        futures.addAll(rollupThreadProfile(rollup, query, auxThreadProfileTable));

        List<String> transactionNames = rollupTransactionSummary(rollup, query, futures);
        futures.addAll(rollupTransactionErrorSummary(rollup, query));
        for (String transactionName : transactionNames) {
            query = ImmutableTransactionQuery.builder()
                    .transactionType(transactionType)
                    .transactionName(transactionName)
                    .from(from)
                    .to(to)
                    .rollupLevel(rollup.rollupLevel() - 1)
                    .build();
            futures.addAll(rollupOverview(rollup, query));
            futures.addAll(rollupHistogram(rollup, query, scratchBuffer));
            futures.addAll(rollupThroughput(rollup, query));
            futures.addAll(rollupQueries(rollup, query));
            futures.addAll(rollupServiceCalls(rollup, query));
            futures.addAll(rollupThreadProfile(rollup, query, mainThreadProfileTable));
            futures.addAll(rollupThreadProfile(rollup, query, auxThreadProfileTable));
        }
        Futures.allAsList(futures).get();
    }

    private List<ResultSetFuture> rollupOverallSummary(RollupParams rollup,
            TransactionQuery query) {
        ResultSet results = executeQueryForRollup(rollup.agentRollup(), query, summaryTable);
        if (results.isExhausted()) {
            // this probably shouldn't happen
            return ImmutableList.of();
        }
        double totalDurationNanos = 0;
        long transactionCount = 0;
        for (Row row : results) {
            totalDurationNanos += row.getDouble(0);
            transactionCount += row.getLong(1);
        }
        BoundStatement boundStatement =
                getInsertOverallPS(summaryTable, rollup.rollupLevel()).bind();
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setInt(i++, rollup.ttl());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupErrorSummary(RollupParams rollup, TransactionQuery query) {
        ResultSet results = executeQueryForRollup(rollup.agentRollup(), query, errorSummaryTable);
        if (results.isExhausted()) {
            return ImmutableList.of();
        }
        long errorCount = 0;
        long transactionCount = 0;
        for (Row row : results) {
            errorCount += row.getLong(0);
            transactionCount += row.getLong(1);
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(errorSummaryTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(errorSummaryTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.to()));
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setLong(i++, errorCount);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setInt(i++, rollup.ttl());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupOverview(RollupParams rollup, TransactionQuery query)
            throws IOException {
        ResultSet results = executeQueryForRollup(rollup.agentRollup(), query, overviewTable);
        if (results.isExhausted()) {
            // this probably shouldn't happen
            return ImmutableList.of();
        }
        double totalDurationNanos = 0;
        long transactionCount = 0;
        boolean asyncTransactions = false;
        List<MutableTimer> mainThreadRootTimers = Lists.newArrayList();
        List<MutableTimer> auxThreadRootTimers = Lists.newArrayList();
        List<MutableTimer> asyncTimers = Lists.newArrayList();
        MutableThreadStats mainThreadStats = new MutableThreadStats();
        MutableThreadStats auxThreadStats = new MutableThreadStats();
        for (Row row : results) {
            int i = 0;
            totalDurationNanos += row.getDouble(i++);
            transactionCount += row.getLong(i++);
            if (row.getBool(i++)) {
                asyncTransactions = true;
            }
            List<Aggregate.Timer> toBeMergedMainThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            MutableAggregate.mergeRootTimers(toBeMergedMainThreadRootTimers, mainThreadRootTimers);
            List<Aggregate.Timer> toBeMergedAuxThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            MutableAggregate.mergeRootTimers(toBeMergedAuxThreadRootTimers, auxThreadRootTimers);
            List<Aggregate.Timer> toBeMergedAsyncTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            MutableAggregate.mergeRootTimers(toBeMergedAsyncTimers, asyncTimers);
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
            boundStatement = getInsertOverallPS(overviewTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(overviewTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setBool(i++, asyncTransactions);
        boundStatement.setBytes(i++,
                Messages.toByteBuffer(MutableAggregate.toProto(mainThreadRootTimers)));
        boundStatement.setBytes(i++,
                Messages.toByteBuffer(MutableAggregate.toProto(auxThreadRootTimers)));
        boundStatement.setBytes(i++,
                Messages.toByteBuffer(MutableAggregate.toProto(asyncTimers)));
        boundStatement.setBytes(i++, toByteBuffer(mainThreadStats.toProto()));
        boundStatement.setBytes(i++, toByteBuffer(auxThreadStats.toProto()));
        boundStatement.setInt(i++, rollup.ttl());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupHistogram(RollupParams rollup, TransactionQuery query,
            ScratchBuffer scratchBuffer) throws Exception {
        ResultSet results = executeQueryForRollup(rollup.agentRollup(), query, histogramTable);
        if (results.isExhausted()) {
            // this probably shouldn't happen
            return ImmutableList.of();
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
            boundStatement = getInsertOverallPS(histogramTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(histogramTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setBytes(i++, toByteBuffer(durationNanosHistogram.toProto(scratchBuffer)));
        boundStatement.setInt(i++, rollup.ttl());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupThroughput(RollupParams rollup, TransactionQuery query) {
        ResultSet results = executeQueryForRollup(rollup.agentRollup(), query, throughputTable);
        if (results.isExhausted()) {
            // this probably shouldn't happen
            return ImmutableList.of();
        }
        long transactionCount = 0;
        for (Row row : results) {
            transactionCount += row.getLong(0);
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(throughputTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(throughputTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setInt(i++, rollup.ttl());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupQueries(RollupParams rollup, TransactionQuery query)
            throws IOException {
        ResultSet results = executeQueryForRollup(rollup.agentRollup(), query, queriesTable);
        if (results.isExhausted()) {
            return ImmutableList.of();
        }
        QueryCollector collector = new QueryCollector(rollup.maxAggregateQueriesPerType(), 0);
        for (Row row : results) {
            ByteBuffer bytes = checkNotNull(row.getBytes(0));
            collector.mergeQueries(
                    Messages.parseDelimitedFrom(bytes, Aggregate.QueriesByType.parser()));
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(queriesTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(queriesTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setBytes(i++, Messages.toByteBuffer(collector.toProto()));
        boundStatement.setInt(i++, rollup.ttl());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupServiceCalls(RollupParams rollup, TransactionQuery query)
            throws IOException {
        ResultSet results = executeQueryForRollup(rollup.agentRollup(), query, serviceCallsTable);
        if (results.isExhausted()) {
            return ImmutableList.of();
        }
        ServiceCallCollector collector =
                new ServiceCallCollector(rollup.maxAggregateServiceCallsPerType(), 0);
        for (Row row : results) {
            ByteBuffer bytes = checkNotNull(row.getBytes(0));
            collector.mergeServiceCalls(
                    Messages.parseDelimitedFrom(bytes, Aggregate.ServiceCallsByType.parser()));
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(serviceCallsTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(serviceCallsTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setBytes(i++, Messages.toByteBuffer(collector.toProto()));
        boundStatement.setInt(i++, rollup.ttl());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupThreadProfile(RollupParams rollup, TransactionQuery query,
            Table table) throws InvalidProtocolBufferException {
        ResultSet results = executeQueryForRollup(rollup.agentRollup(), query, table);
        if (results.isExhausted()) {
            return ImmutableList.of();
        }
        MutableProfile profile = new MutableProfile();
        for (Row row : results) {
            ByteBuffer bytes = checkNotNull(row.getBytes(0));
            profile.merge(Profile.parseFrom(ByteString.copyFrom(bytes)));
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(table, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(table, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollup());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setBytes(i++, toByteBuffer(profile.toProto()));
        boundStatement.setInt(i++, rollup.ttl());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<String> rollupTransactionSummary(RollupParams rollup, TransactionQuery query,
            List<ResultSetFuture> futures) {
        BoundStatement boundStatement = checkNotNull(readTransactionForRollupPS.get(summaryTable))
                .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, rollup.agentRollup(), query);
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
        PreparedStatement preparedStatement =
                getInsertTransactionPS(summaryTable, rollup.rollupLevel());
        for (Entry<String, MutableSummary> entry : summaries.entrySet()) {
            MutableSummary summary = entry.getValue();
            boundStatement = preparedStatement.bind();
            int i = 0;
            boundStatement.setString(i++, rollup.agentRollup());
            boundStatement.setString(i++, query.transactionType());
            boundStatement.setTimestamp(i++, new Date(query.to()));
            boundStatement.setString(i++, entry.getKey());
            boundStatement.setDouble(i++, summary.totalDurationNanos);
            boundStatement.setLong(i++, summary.transactionCount);
            boundStatement.setInt(i++, rollup.ttl());
            futures.add(session.executeAsync(boundStatement));
        }
        return ImmutableList.copyOf(summaries.keySet());
    }

    private List<ResultSetFuture> rollupTransactionErrorSummary(RollupParams rollup,
            TransactionQuery query) {
        BoundStatement boundStatement =
                checkNotNull(readTransactionForRollupPS.get(errorSummaryTable))
                        .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, rollup.agentRollup(), query);
        ResultSet results = session.execute(boundStatement);
        if (results.isExhausted()) {
            return ImmutableList.of();
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
                getInsertTransactionPS(errorSummaryTable, rollup.rollupLevel());
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (Entry<String, MutableErrorSummary> entry : summaries.entrySet()) {
            MutableErrorSummary summary = entry.getValue();
            boundStatement = preparedStatement.bind();
            int i = 0;
            boundStatement.setString(i++, rollup.agentRollup());
            boundStatement.setString(i++, query.transactionType());
            boundStatement.setTimestamp(i++, new Date(query.to()));
            boundStatement.setString(i++, entry.getKey());
            boundStatement.setLong(i++, summary.errorCount);
            boundStatement.setLong(i++, summary.transactionCount);
            boundStatement.setInt(i++, rollup.ttl());
            futures.add(session.executeAsync(boundStatement));
        }
        return futures;
    }

    private List<ResultSetFuture> storeOverallAggregate(String agentRollup, String transactionType,
            long captureTime, Aggregate aggregate, int ttl) throws IOException {

        final int rollupLevel = 0;

        List<ResultSetFuture> futures = Lists.newArrayList();
        BoundStatement boundStatement = getInsertOverallPS(summaryTable, rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, ttl);
        futures.add(session.executeAsync(boundStatement));

        if (aggregate.getErrorCount() > 0) {
            boundStatement = getInsertOverallPS(errorSummaryTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setLong(i++, aggregate.getErrorCount());
            boundStatement.setLong(i++, aggregate.getTransactionCount());
            boundStatement.setInt(i++, ttl);
            futures.add(session.executeAsync(boundStatement));
        }

        boundStatement = getInsertOverallPS(overviewTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, i++, ttl);
        futures.add(session.executeAsync(boundStatement));

        boundStatement = getInsertOverallPS(histogramTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setBytes(i++, toByteBuffer(aggregate.getDurationNanosHistogram()));
        boundStatement.setInt(i++, ttl);
        futures.add(session.executeAsync(boundStatement));

        boundStatement = getInsertOverallPS(throughputTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, ttl);
        futures.add(session.executeAsync(boundStatement));

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            boundStatement = getInsertOverallPS(mainThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, ttl);
            futures.add(session.executeAsync(boundStatement));
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            boundStatement = getInsertOverallPS(auxThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, ttl);
            futures.add(session.executeAsync(boundStatement));
        }
        List<QueriesByType> queriesByTypeList = aggregate.getQueriesByTypeList();
        if (!queriesByTypeList.isEmpty()) {
            boundStatement = getInsertOverallPS(queriesTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, Messages.toByteBuffer(queriesByTypeList));
            boundStatement.setInt(i++, ttl);
            futures.add(session.executeAsync(boundStatement));
        }
        return futures;
    }

    private List<ResultSetFuture> storeTransactionAggregate(String agentRollup,
            String transactionType, String transactionName, long captureTime, Aggregate aggregate,
            int ttl) throws IOException {

        final int rollupLevel = 0;

        List<ResultSetFuture> futures = Lists.newArrayList();
        BoundStatement boundStatement = getInsertTransactionPS(summaryTable, rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setString(i++, transactionName);
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, ttl);
        futures.add(session.executeAsync(boundStatement));

        if (aggregate.getErrorCount() > 0) {
            boundStatement = getInsertTransactionPS(errorSummaryTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setString(i++, transactionName);
            boundStatement.setLong(i++, aggregate.getErrorCount());
            boundStatement.setLong(i++, aggregate.getTransactionCount());
            boundStatement.setInt(i++, ttl);
            futures.add(session.executeAsync(boundStatement));
        }

        boundStatement = getInsertTransactionPS(overviewTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, transactionName);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, i++, ttl);
        futures.add(session.executeAsync(boundStatement));

        boundStatement = getInsertTransactionPS(histogramTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, transactionName);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setBytes(i++, toByteBuffer(aggregate.getDurationNanosHistogram()));
        boundStatement.setInt(i++, ttl);
        futures.add(session.executeAsync(boundStatement));

        boundStatement = getInsertTransactionPS(throughputTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, transactionName);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, ttl);
        futures.add(session.executeAsync(boundStatement));

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            boundStatement = getInsertTransactionPS(mainThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setString(i++, transactionType);
            boundStatement.setString(i++, transactionName);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, ttl);
            futures.add(session.executeAsync(boundStatement));
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            boundStatement = getInsertTransactionPS(auxThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setString(i++, transactionType);
            boundStatement.setString(i++, transactionName);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, ttl);
            futures.add(session.executeAsync(boundStatement));
        }
        List<QueriesByType> queriesByTypeList = aggregate.getQueriesByTypeList();
        if (!queriesByTypeList.isEmpty()) {
            boundStatement = getInsertTransactionPS(queriesTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setString(i++, transactionType);
            boundStatement.setString(i++, transactionName);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, Messages.toByteBuffer(queriesByTypeList));
            boundStatement.setInt(i++, ttl);
            futures.add(session.executeAsync(boundStatement));
        }
        return futures;
    }

    private PreparedStatement getInsertOverallPS(Table table, int rollupLevel) {
        return checkNotNull(insertOverallPS.get(table)).get(rollupLevel);
    }

    private PreparedStatement getInsertTransactionPS(Table table, int rollupLevel) {
        return checkNotNull(insertTransactionPS.get(table)).get(rollupLevel);
    }

    private void bindAggregate(BoundStatement boundStatement, Aggregate aggregate, int startIndex,
            int ttl) throws IOException {
        int i = startIndex;
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setBool(i++, aggregate.getAsyncTransactions());
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
        List<Timer> asyncTimers = aggregate.getAsyncTimerList();
        if (!asyncTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(asyncTimers));
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
        boundStatement.setInt(i++, ttl);
    }

    private ResultSet createBoundStatement(String agentRollup, OverallQuery query, Table table) {
        BoundStatement boundStatement =
                checkNotNull(readOverallPS.get(table)).get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollup, query);
        return session.execute(boundStatement);
    }

    private ResultSet executeQuery(String agentRollup, TransactionQuery query, Table table) {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = checkNotNull(readOverallPS.get(table)).get(query.rollupLevel()).bind();
        } else {
            boundStatement =
                    checkNotNull(readTransactionPS.get(table)).get(query.rollupLevel()).bind();
        }
        bindQuery(boundStatement, agentRollup, query);
        return session.execute(boundStatement);
    }

    private ResultSet executeQueryForRollup(String agentRollup, TransactionQuery query,
            Table table) {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement =
                    checkNotNull(readOverallForRollupPS.get(table)).get(query.rollupLevel()).bind();
        } else {
            boundStatement = checkNotNull(readTransactionForRollupPS.get(table))
                    .get(query.rollupLevel()).bind();
        }
        bindQuery(boundStatement, agentRollup, query);
        return session.execute(boundStatement);
    }

    private void mergeInProfiles(String agentRollup, TransactionQuery query, Table profileTable,
            ProfileCollector collector) throws InvalidProtocolBufferException {
        ResultSet results = executeQuery(agentRollup, query, profileTable);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(0)).getTime());
            ByteBuffer bytes = checkNotNull(row.getBytes(1));
            // TODO optimize this byte copying
            Profile profile = Profile.parseFrom(ByteString.copyFrom(bytes));
            collector.mergeProfile(profile);
            collector.updateLastCaptureTime(captureTime);
        }
    }

    private List<Integer> getTTLs() {
        List<Integer> ttls = Lists.newArrayList();
        List<Integer> rollupExpirationHours =
                configRepository.getStorageConfig().rollupExpirationHours();
        for (long expirationHours : rollupExpirationHours) {
            ttls.add(Ints.saturatedCast(HOURS.toSeconds(expirationHours)));
        }
        return ttls;
    }

    private RollupParams getRollupParams(String agentId, int rollupLevel, int ttl)
            throws IOException {
        ImmutableRollupParams.Builder rollupInfo = ImmutableRollupParams.builder()
                .agentRollup(agentId)
                .rollupLevel(rollupLevel)
                .ttl(ttl);
        AdvancedConfig advancedConfig = configRepository.getAdvancedConfig(agentId);
        if (advancedConfig != null && advancedConfig.hasMaxAggregateQueriesPerType()) {
            rollupInfo.maxAggregateQueriesPerType(
                    advancedConfig.getMaxAggregateQueriesPerType().getValue());
        } else {
            rollupInfo.maxAggregateQueriesPerType(ConfigDefaults.MAX_AGGREGATE_QUERIES_PER_TYPE);
        }
        if (advancedConfig != null && advancedConfig.hasMaxAggregateServiceCallsPerType()) {
            rollupInfo.maxAggregateServiceCallsPerType(
                    advancedConfig.getMaxAggregateServiceCallsPerType().getValue());
        } else {
            rollupInfo.maxAggregateServiceCallsPerType(
                    ConfigDefaults.MAX_AGGREGATE_SERVICE_CALLS_PER_TYPE);
        }
        return rollupInfo.build();
    }

    private static void bindQuery(BoundStatement boundStatement, String agentRollup,
            OverallQuery query) {
        int i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.from()));
        boundStatement.setTimestamp(i++, new Date(query.to()));
    }

    private static void bindQuery(BoundStatement boundStatement, String agentRollup,
            TransactionQuery query) {
        int i = 0;
        boundStatement.setString(i++, agentRollup);
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
                    + " capture_time)) ");
        } else {
            sb.append(", primary key ((agent_rollup, transaction_type), capture_time)) ");
        }
        sb.append(WITH_DTCS);
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
        sb.append(") using TTL ?");
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
                    + " capture_time, transaction_name)) ");
        } else {
            sb.append(", primary key ((agent_rollup, transaction_type), capture_time)) ");
        }
        sb.append(WITH_DTCS);
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
        sb.append(") using TTL ?");
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

    private static String getTableName(String partialName, boolean transaction, int i) {
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
        return sb.toString();
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

    @Value.Immutable
    interface RollupParams {
        String agentRollup();
        int rollupLevel();
        int ttl();
        int maxAggregateQueriesPerType();
        int maxAggregateServiceCallsPerType();
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
