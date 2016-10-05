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
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.immutables.value.Value;

import org.glowroot.common.config.ConfigDefaults;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector.SummarySortOrder;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.MutableAggregate;
import org.glowroot.common.repo.MutableThreadStats;
import org.glowroot.common.repo.MutableTimer;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Styles;
import org.glowroot.server.util.Messages;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OldTransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.Proto.OptionalDouble;

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
            .addColumns(ImmutableColumn.of("main_thread_total_cpu_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("main_thread_total_blocked_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("main_thread_total_waited_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("main_thread_total_allocated_bytes", "double")) // nullable
            .addColumns(ImmutableColumn.of("aux_thread_total_cpu_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("aux_thread_total_blocked_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("aux_thread_total_waited_nanos", "double")) // nullable
            .addColumns(ImmutableColumn.of("aux_thread_total_allocated_bytes", "double")) // nullable
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

    private static final Table queryTable = ImmutableTable.builder()
            .partialName("query")
            .addColumns(ImmutableColumn.of("query_type", "varchar"))
            .addColumns(ImmutableColumn.of("truncated_query_text", "varchar"))
            // empty when truncated_query_text is really full query text
            // (not null since this column must be used in clustering key)
            .addColumns(ImmutableColumn.of("full_query_text_sha1", "varchar"))
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("execution_count", "bigint"))
            .addColumns(ImmutableColumn.of("total_rows", "bigint"))
            .addClusterKey("query_type")
            .addClusterKey("truncated_query_text")
            .addClusterKey("full_query_text_sha1") // need this for uniqueness
            .summary(false)
            .fromInclusive(false)
            .build();

    private static final Table serviceCallTable = ImmutableTable.builder()
            .partialName("service_call")
            .addColumns(ImmutableColumn.of("service_call_type", "varchar"))
            .addColumns(ImmutableColumn.of("service_call_text", "varchar"))
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("execution_count", "bigint"))
            .addClusterKey("service_call_type")
            .addClusterKey("service_call_text")
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

    private final FullQueryTextDao fullQueryTextDao;

    // list index is rollupLevel
    private final Map<Table, List<PreparedStatement>> insertOverallPS;
    private final Map<Table, List<PreparedStatement>> insertTransactionPS;
    private final Map<Table, List<PreparedStatement>> readOverallPS;
    private final Map<Table, List<PreparedStatement>> readOverallForRollupPS;
    private final Map<Table, List<PreparedStatement>> readTransactionPS;
    private final Map<Table, List<PreparedStatement>> readTransactionForRollupPS;

    private final List<PreparedStatement> existsMainThreadProfileOverallPS;
    private final List<PreparedStatement> existsMainThreadProfileTransactionPS;
    private final List<PreparedStatement> existsAuxThreadProfileOverallPS;
    private final List<PreparedStatement> existsAuxThreadProfileTransactionPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    private final ImmutableList<Table> allTables;

    public AggregateDao(Session session, TransactionTypeDao transactionTypeDao,
            FullQueryTextDao fullQueryTextDao, ConfigRepository configRepository) {
        this.session = session;
        this.transactionTypeDao = transactionTypeDao;
        this.configRepository = configRepository;
        this.fullQueryTextDao = fullQueryTextDao;

        int count = configRepository.getRollupConfigs().size();

        allTables = ImmutableList.of(summaryTable, errorSummaryTable, overviewTable,
                histogramTable, throughputTable, queryTable, serviceCallTable,
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

        List<PreparedStatement> existsMainThreadProfileOverallPS = Lists.newArrayList();
        List<PreparedStatement> existsMainThreadProfileTransactionPS = Lists.newArrayList();
        List<PreparedStatement> existsAuxThreadProfileOverallPS = Lists.newArrayList();
        List<PreparedStatement> existsAuxThreadProfileTransactionPS = Lists.newArrayList();
        for (int i = 0; i < count; i++) {
            existsMainThreadProfileOverallPS
                    .add(session.prepare(existsPS(mainThreadProfileTable, false, i)));
            existsMainThreadProfileTransactionPS
                    .add(session.prepare(existsPS(mainThreadProfileTable, true, i)));
            existsAuxThreadProfileOverallPS
                    .add(session.prepare(existsPS(auxThreadProfileTable, false, i)));
            existsAuxThreadProfileTransactionPS
                    .add(session.prepare(existsPS(auxThreadProfileTable, true, i)));
        }
        this.existsMainThreadProfileOverallPS = existsMainThreadProfileOverallPS;
        this.existsMainThreadProfileTransactionPS = existsMainThreadProfileTransactionPS;
        this.existsAuxThreadProfileOverallPS = existsAuxThreadProfileOverallPS;
        this.existsAuxThreadProfileTransactionPS = existsAuxThreadProfileTransactionPS;

        List<PreparedStatement> insertNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> readNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> deleteNeedsRollup = Lists.newArrayList();
        for (int i = 1; i < count; i++) {
            session.execute("create table if not exists aggregate_needs_rollup_" + i
                    + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                    + " transaction_types set<varchar>, primary key (agent_rollup, capture_time,"
                    + " uniqueness)) " + WITH_LCS);
            insertNeedsRollup.add(session.prepare("insert into aggregate_needs_rollup_" + i
                    + " (agent_rollup, capture_time, uniqueness, transaction_types) values"
                    + " (?, ?, ?, ?)"));
            readNeedsRollup.add(session.prepare("select capture_time, uniqueness, transaction_types"
                    + " from aggregate_needs_rollup_" + i + " where agent_rollup = ?"));
            deleteNeedsRollup.add(session.prepare("delete from aggregate_needs_rollup_" + i
                    + " where agent_rollup = ? and capture_time = ? and uniqueness = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;
    }

    public void store(String agentId, long captureTime,
            List<OldAggregatesByType> aggregatesByTypeList,
            List<Aggregate.SharedQueryText> initialSharedQueryTexts) throws Exception {
        int adjustedTTL = GaugeValueDao.getAdjustedTTL(getTTLs().get(0), captureTime);
        List<ResultSetFuture> futures = Lists.newArrayList();
        List<Aggregate.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
        for (Aggregate.SharedQueryText sharedQueryText : initialSharedQueryTexts) {
            String fullTextSha1 = sharedQueryText.getFullTextSha1();
            if (fullTextSha1.isEmpty()) {
                String fullText = sharedQueryText.getFullText();
                if (fullText.length() > 120) {
                    fullTextSha1 = fullQueryTextDao.store(agentId, fullText, futures);
                    sharedQueryTexts.add(Aggregate.SharedQueryText.newBuilder()
                            .setTruncatedText(fullText.substring(0, 120))
                            .setFullTextSha1(fullTextSha1)
                            .build());
                    sharedQueryTexts.add(sharedQueryText);
                } else {
                    sharedQueryTexts.add(sharedQueryText);
                }
            } else {
                fullQueryTextDao.updateTTL(agentId, fullTextSha1, futures);
                sharedQueryTexts.add(sharedQueryText);
            }
        }
        for (OldAggregatesByType aggregatesByType : aggregatesByTypeList) {
            String transactionType = aggregatesByType.getTransactionType();
            Aggregate overallAggregate = aggregatesByType.getOverallAggregate();
            futures.addAll(storeOverallAggregate(agentId, transactionType, captureTime,
                    overallAggregate, sharedQueryTexts, adjustedTTL));
            for (OldTransactionAggregate transactionAggregate : aggregatesByType
                    .getTransactionAggregateList()) {
                futures.addAll(storeTransactionAggregate(agentId, transactionType,
                        transactionAggregate.getTransactionName(), captureTime,
                        transactionAggregate.getAggregate(), sharedQueryTexts, adjustedTTL));
            }
            transactionTypeDao.store(agentId, transactionType, futures);
        }
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        // TODO report checker framework issue that occurs without this suppression
        @SuppressWarnings("assignment.type.incompatible")
        Set<String> transactionTypes = aggregatesByTypeList.stream()
                .map(OldAggregatesByType::getTransactionType).collect(Collectors.toSet());
        for (int i = 1; i < rollupConfigs.size(); i++) {
            long intervalMillis = rollupConfigs.get(i).intervalMillis();
            long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, intervalMillis);
            BoundStatement boundStatement = insertNeedsRollup.get(i - 1).bind();
            boundStatement.setString(0, agentId);
            boundStatement.setTimestamp(1, new Date(rollupCaptureTime));
            boundStatement.setUUID(2, UUIDs.timeBased());
            boundStatement.setSet(3, transactionTypes);
            futures.add(session.executeAsync(boundStatement));
        }
        Futures.allAsList(futures).get();
    }

    // query.from() is non-inclusive
    @Override
    public void mergeOverallSummaryInto(String agentRollup, OverallQuery query,
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
    public void mergeTransactionSummariesInto(String agentRollup, OverallQuery query,
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
    public void mergeOverallErrorSummaryInto(String agentRollup, OverallQuery query,
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
    public void mergeTransactionErrorSummariesInto(String agentRollup, OverallQuery query,
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
            Double mainThreadTotalCpuNanos = row.get(i++, Double.class);
            Double mainThreadTotalBlockedNanos = row.get(i++, Double.class);
            Double mainThreadTotalWaitedNanos = row.get(i++, Double.class);
            Double mainThreadTotalAllocatedBytes = row.get(i++, Double.class);
            Aggregate.ThreadStats mainThreadStats =
                    buildThreadStats(mainThreadTotalCpuNanos, mainThreadTotalBlockedNanos,
                            mainThreadTotalWaitedNanos, mainThreadTotalAllocatedBytes);
            if (mainThreadStats != null) {
                builder.mainThreadStats(mainThreadStats);
            }
            Double auxThreadTotalCpuNanos = row.get(i++, Double.class);
            Double auxThreadTotalBlockedNanos = row.get(i++, Double.class);
            Double auxThreadTotalWaitedNanos = row.get(i++, Double.class);
            Double auxThreadTotalAllocatedBytes = row.get(i++, Double.class);
            Aggregate.ThreadStats auxThreadStats =
                    buildThreadStats(auxThreadTotalCpuNanos, auxThreadTotalBlockedNanos,
                            auxThreadTotalWaitedNanos, auxThreadTotalAllocatedBytes);
            if (auxThreadStats != null) {
                builder.auxThreadStats(auxThreadStats);
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

    @Override
    public @Nullable String readFullQueryText(String agentRollup, String fullQueryTextSha1)
            throws Exception {
        return fullQueryTextDao.getFullText(agentRollup, fullQueryTextSha1);
    }

    // query.from() is non-inclusive
    @Override
    public void mergeQueriesInto(String agentRollup, TransactionQuery query,
            QueryCollector collector) throws IOException {
        ResultSet results = executeQuery(agentRollup, query, queryTable);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            int i = 0;
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(i++)).getTime());
            String queryType = checkNotNull(row.getString(i++));
            String truncatedText = checkNotNull(row.getString(i++));
            // full_query_text_sha1 cannot be null since it is used in clustering key
            String fullTextSha1 = Strings.emptyToNull(row.getString(i++));
            double totalDurationNanos = row.getDouble(i++);
            long executionCount = row.getLong(i++);
            boolean hasTotalRows = !row.isNull(i);
            long totalRows = row.getLong(i++);
            collector.mergeQuery(queryType, truncatedText, fullTextSha1, totalDurationNanos,
                    executionCount, hasTotalRows, totalRows);
            collector.updateLastCaptureTime(captureTime);
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeServiceCallsInto(String agentRollup, TransactionQuery query,
            ServiceCallCollector collector) throws IOException {
        ResultSet results = executeQuery(agentRollup, query, serviceCallTable);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            int i = 0;
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(i++)).getTime());
            String serviceCallType = checkNotNull(row.getString(i++));
            String serviceCallText = checkNotNull(row.getString(i++));
            double totalDurationNanos = row.getDouble(i++);
            long executionCount = row.getLong(i++);
            collector.mergeServiceCall(serviceCallType, serviceCallText, totalDurationNanos,
                    executionCount);
            collector.updateLastCaptureTime(captureTime);
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeMainThreadProfilesInto(String agentRollup, TransactionQuery query,
            ProfileCollector collector) throws InvalidProtocolBufferException {
        mergeProfilesInto(agentRollup, query, mainThreadProfileTable, collector);
    }

    // query.from() is non-inclusive
    @Override
    public void mergeAuxThreadProfilesInto(String agentRollup, TransactionQuery query,
            ProfileCollector collector) throws InvalidProtocolBufferException {
        mergeProfilesInto(agentRollup, query, auxThreadProfileTable, collector);
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasMainThreadProfile(String agentRollup, TransactionQuery query)
            throws Exception {
        BoundStatement boundStatement = query.transactionName() == null
                ? existsMainThreadProfileOverallPS.get(query.rollupLevel()).bind()
                : existsMainThreadProfileTransactionPS.get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollup, query);
        ResultSet results = session.execute(boundStatement);
        return results.one() != null;
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

    public void rollup(String agentRollup) throws Exception {
        List<Integer> ttls = getTTLs();
        for (int rollupLevel = 1; rollupLevel < configRepository.getRollupConfigs()
                .size(); rollupLevel++) {
            int ttl = ttls.get(rollupLevel);
            rollupLevel(agentRollup, rollupLevel, ttl);
        }
    }

    private void rollupLevel(String agentRollup, int rollupLevel, int ttl) throws Exception {
        List<NeedsRollup> needsRollupList =
                getNeedsRollupList(agentRollup, rollupLevel, readNeedsRollup, session);
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long rollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
        Long nextRollupIntervalMillis = null;
        if (rollupLevel + 1 < rollupConfigs.size()) {
            nextRollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
        }
        for (NeedsRollup rollupContent : needsRollupList) {
            long captureTime = rollupContent.getCaptureTime();
            int adjustedTTL = GaugeValueDao.getAdjustedTTL(ttl, captureTime);
            RollupParams rollupParams = getRollupParams(agentRollup, rollupLevel, adjustedTTL);
            long from = captureTime - rollupIntervalMillis;
            for (String transactionType : rollupContent.getKeys()) {
                rollupOne(rollupParams, transactionType, from, captureTime);
            }
            postRollup(agentRollup, rollupLevel, rollupContent, nextRollupIntervalMillis,
                    insertNeedsRollup, deleteNeedsRollup, session);
        }
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
        boundStatement.setInt(i++, rollup.adjustedTTL());
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
        boundStatement.setInt(i++, rollup.adjustedTTL());
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
            mainThreadStats.addTotalCpuNanos(row.get(i++, Double.class));
            mainThreadStats.addTotalBlockedNanos(row.get(i++, Double.class));
            mainThreadStats.addTotalWaitedNanos(row.get(i++, Double.class));
            mainThreadStats.addTotalAllocatedBytes(row.get(i++, Double.class));
            auxThreadStats.addTotalCpuNanos(row.get(i++, Double.class));
            auxThreadStats.addTotalBlockedNanos(row.get(i++, Double.class));
            auxThreadStats.addTotalWaitedNanos(row.get(i++, Double.class));
            auxThreadStats.addTotalAllocatedBytes(row.get(i++, Double.class));
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
        boundStatement.setDouble(i++, mainThreadStats.getTotalCpuNanos());
        boundStatement.setDouble(i++, mainThreadStats.getTotalBlockedNanos());
        boundStatement.setDouble(i++, mainThreadStats.getTotalWaitedNanos());
        boundStatement.setDouble(i++, mainThreadStats.getTotalAllocatedBytes());
        boundStatement.setDouble(i++, auxThreadStats.getTotalCpuNanos());
        boundStatement.setDouble(i++, auxThreadStats.getTotalBlockedNanos());
        boundStatement.setDouble(i++, auxThreadStats.getTotalWaitedNanos());
        boundStatement.setDouble(i++, auxThreadStats.getTotalAllocatedBytes());
        boundStatement.setInt(i++, rollup.adjustedTTL());
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
        boundStatement.setInt(i++, rollup.adjustedTTL());
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
        boundStatement.setInt(i++, rollup.adjustedTTL());
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    private List<ResultSetFuture> rollupQueries(RollupParams rollup, TransactionQuery query)
            throws IOException {
        ResultSet results = executeQueryForRollup(rollup.agentRollup(), query, queryTable);
        if (results.isExhausted()) {
            return ImmutableList.of();
        }
        QueryCollector collector = new QueryCollector(rollup.maxAggregateQueriesPerType());
        for (Row row : results) {
            int i = 0;
            String queryType = checkNotNull(row.getString(i++));
            String truncatedText = checkNotNull(row.getString(i++));
            // full_query_text_sha1 cannot be null since it is used in clustering key
            String fullTextSha1 = Strings.emptyToNull(row.getString(i++));
            double totalDurationNanos = row.getDouble(i++);
            long executionCount = row.getLong(i++);
            boolean hasTotalRows = !row.isNull(i);
            long totalRows = row.getLong(i++);
            collector.mergeQuery(queryType, truncatedText, fullTextSha1, totalDurationNanos,
                    executionCount, hasTotalRows, totalRows);
        }
        return insertQueries(collector.getSortedQueries(), rollup.rollupLevel(),
                rollup.agentRollup(), query.transactionType(), query.transactionName(), query.to(),
                rollup.adjustedTTL());
    }

    private List<ResultSetFuture> rollupServiceCalls(RollupParams rollup, TransactionQuery query)
            throws IOException {
        ResultSet results = executeQueryForRollup(rollup.agentRollup(), query, serviceCallTable);
        if (results.isExhausted()) {
            return ImmutableList.of();
        }
        ServiceCallCollector collector =
                new ServiceCallCollector(rollup.maxAggregateServiceCallsPerType(), 0);
        for (Row row : results) {
            int i = 0;
            String serviceCallType = checkNotNull(row.getString(i++));
            String serviceCallText = checkNotNull(row.getString(i++));
            double totalDurationNanos = row.getDouble(i++);
            long executionCount = row.getLong(i++);
            collector.mergeServiceCall(serviceCallType, serviceCallText, totalDurationNanos,
                    executionCount);
        }
        return insertServiceCalls(collector.toProto(), rollup.rollupLevel(), rollup.agentRollup(),
                query.transactionType(), query.transactionName(), query.to(), rollup.adjustedTTL());
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
        boundStatement.setInt(i++, rollup.adjustedTTL());
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
            boundStatement.setInt(i++, rollup.adjustedTTL());
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
            boundStatement.setInt(i++, rollup.adjustedTTL());
            futures.add(session.executeAsync(boundStatement));
        }
        return futures;
    }

    private List<ResultSetFuture> storeOverallAggregate(String agentRollup, String transactionType,
            long captureTime, Aggregate aggregate, List<Aggregate.SharedQueryText> sharedQueryTexts,
            int adjustedTTL) throws Exception {

        final int rollupLevel = 0;

        List<ResultSetFuture> futures = Lists.newArrayList();
        BoundStatement boundStatement = getInsertOverallPS(summaryTable, rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        if (aggregate.getErrorCount() > 0) {
            boundStatement = getInsertOverallPS(errorSummaryTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setLong(i++, aggregate.getErrorCount());
            boundStatement.setLong(i++, aggregate.getTransactionCount());
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }

        boundStatement = getInsertOverallPS(overviewTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        boundStatement = getInsertOverallPS(histogramTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setBytes(i++, toByteBuffer(aggregate.getDurationNanosHistogram()));
        boundStatement.setInt(i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        boundStatement = getInsertOverallPS(throughputTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            boundStatement = getInsertOverallPS(mainThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, adjustedTTL);
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
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }
        futures.addAll(insertQueries(aggregate.getQueriesByTypeList(), sharedQueryTexts,
                rollupLevel, agentRollup, transactionType, null, captureTime, adjustedTTL));
        futures.addAll(insertServiceCalls(aggregate.getServiceCallsByTypeList(), rollupLevel,
                agentRollup, transactionType, null, captureTime, adjustedTTL));
        return futures;
    }

    private List<ResultSetFuture> storeTransactionAggregate(String agentRollup,
            String transactionType, String transactionName, long captureTime, Aggregate aggregate,
            List<Aggregate.SharedQueryText> sharedQueryTexts, int adjustedTTL) throws IOException {

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
        boundStatement.setInt(i++, adjustedTTL);
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
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }

        boundStatement = getInsertTransactionPS(overviewTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, transactionName);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, i++, adjustedTTL);
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
        boundStatement.setInt(i++, adjustedTTL);
        futures.add(session.executeAsync(boundStatement));

        boundStatement = getInsertTransactionPS(throughputTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, transactionName);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, adjustedTTL);
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
            boundStatement.setInt(i++, adjustedTTL);
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
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }
        futures.addAll(
                insertQueries(aggregate.getQueriesByTypeList(), sharedQueryTexts, rollupLevel,
                        agentRollup, transactionType, transactionName, captureTime, adjustedTTL));
        futures.addAll(insertServiceCalls(aggregate.getServiceCallsByTypeList(), rollupLevel,
                agentRollup, transactionType, transactionName, captureTime, adjustedTTL));
        return futures;
    }

    private List<ResultSetFuture> insertQueries(List<Aggregate.QueriesByType> queriesByTypeList,
            List<Aggregate.SharedQueryText> sharedQueryTexts, int rollupLevel, String agentRollup,
            String transactionType, @Nullable String transactionName, long captureTime,
            int adjustedTTL) {
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (Aggregate.QueriesByType queriesByType : queriesByTypeList) {
            for (Aggregate.Query query : queriesByType.getQueryList()) {
                Aggregate.SharedQueryText sharedQueryText =
                        sharedQueryTexts.get(query.getSharedQueryTextIndex());
                BoundStatement boundStatement;
                if (transactionName == null) {
                    boundStatement = getInsertOverallPS(queryTable, rollupLevel).bind();
                } else {
                    boundStatement = getInsertTransactionPS(queryTable, rollupLevel).bind();
                }
                int i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, transactionType);
                if (transactionName != null) {
                    boundStatement.setString(i++, transactionName);
                }
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setString(i++, queriesByType.getType());
                String fullTextSha1 = sharedQueryText.getFullTextSha1();
                if (fullTextSha1.isEmpty()) {
                    boundStatement.setString(i++, sharedQueryText.getFullText());
                    // full_query_text_sha1 cannot be null since it is used in clustering key
                    boundStatement.setString(i++, "");
                } else {
                    boundStatement.setString(i++, sharedQueryText.getTruncatedText());
                    boundStatement.setString(i++, fullTextSha1);
                }
                boundStatement.setDouble(i++, query.getTotalDurationNanos());
                boundStatement.setLong(i++, query.getExecutionCount());
                if (query.hasTotalRows()) {
                    boundStatement.setLong(i++, query.getTotalRows().getValue());
                } else {
                    boundStatement.setToNull(i++);
                }
                boundStatement.setInt(i++, adjustedTTL);
                futures.add(session.executeAsync(boundStatement));
            }
        }
        return futures;
    }

    private List<ResultSetFuture> insertQueries(Map<String, List<MutableQuery>> map,
            int rollupLevel, String agentRollup, String transactionType,
            @Nullable String transactionName, long captureTime, int adjustedTTL) {
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (Entry<String, List<MutableQuery>> entry : map.entrySet()) {
            for (MutableQuery query : entry.getValue()) {
                BoundStatement boundStatement;
                if (transactionName == null) {
                    boundStatement = getInsertOverallPS(queryTable, rollupLevel).bind();
                } else {
                    boundStatement = getInsertTransactionPS(queryTable, rollupLevel).bind();
                }
                int i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, transactionType);
                if (transactionName != null) {
                    boundStatement.setString(i++, transactionName);
                }
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setString(i++, entry.getKey());
                boundStatement.setString(i++, query.getTruncatedText());
                // full_query_text_sha1 cannot be null since it is used in clustering key
                boundStatement.setString(i++, Strings.nullToEmpty(query.getFullTextSha1()));
                boundStatement.setDouble(i++, query.getTotalDurationNanos());
                boundStatement.setLong(i++, query.getExecutionCount());
                if (query.hasTotalRows()) {
                    boundStatement.setLong(i++, query.getTotalRows());
                } else {
                    boundStatement.setToNull(i++);
                }
                boundStatement.setInt(i++, adjustedTTL);
                futures.add(session.executeAsync(boundStatement));
            }
        }
        return futures;
    }

    private List<ResultSetFuture> insertServiceCalls(
            List<Aggregate.ServiceCallsByType> serviceCallsByTypeList, int rollupLevel,
            String agentRollup, String transactionType, @Nullable String transactionName,
            long captureTime, int adjustedTTL) {
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (Aggregate.ServiceCallsByType serviceCallsByType : serviceCallsByTypeList) {
            for (Aggregate.ServiceCall serviceCall : serviceCallsByType.getServiceCallList()) {
                BoundStatement boundStatement;
                if (transactionName == null) {
                    boundStatement = getInsertOverallPS(serviceCallTable, rollupLevel).bind();
                } else {
                    boundStatement = getInsertTransactionPS(serviceCallTable, rollupLevel).bind();
                }
                int i = 0;
                boundStatement.setString(i++, agentRollup);
                boundStatement.setString(i++, transactionType);
                if (transactionName != null) {
                    boundStatement.setString(i++, transactionName);
                }
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setString(i++, serviceCallsByType.getType());
                boundStatement.setString(i++, serviceCall.getText());
                boundStatement.setDouble(i++, serviceCall.getTotalDurationNanos());
                boundStatement.setLong(i++, serviceCall.getExecutionCount());
                boundStatement.setInt(i++, adjustedTTL);
                futures.add(session.executeAsync(boundStatement));
            }
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
            int adjustedTTL) throws IOException {
        int i = startIndex;
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setBool(i++, aggregate.getAsyncTransactions());
        List<Aggregate.Timer> mainThreadRootTimers = aggregate.getMainThreadRootTimerList();
        if (!mainThreadRootTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(mainThreadRootTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        List<Aggregate.Timer> auxThreadRootTimers = aggregate.getAuxThreadRootTimerList();
        if (!auxThreadRootTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(auxThreadRootTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        List<Aggregate.Timer> asyncTimers = aggregate.getAsyncTimerList();
        if (!asyncTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(asyncTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        Aggregate.ThreadStats mainThreadStats = aggregate.getMainThreadStats();
        if (mainThreadStats.hasTotalCpuNanos()) {
            boundStatement.setDouble(i++, mainThreadStats.getTotalCpuNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (mainThreadStats.hasTotalBlockedNanos()) {
            boundStatement.setDouble(i++, mainThreadStats.getTotalBlockedNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (mainThreadStats.hasTotalWaitedNanos()) {
            boundStatement.setDouble(i++, mainThreadStats.getTotalWaitedNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (mainThreadStats.hasTotalAllocatedBytes()) {
            boundStatement.setDouble(i++, mainThreadStats.getTotalAllocatedBytes().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        Aggregate.ThreadStats auxThreadStats = aggregate.getMainThreadStats();
        if (auxThreadStats.hasTotalCpuNanos()) {
            boundStatement.setDouble(i++, auxThreadStats.getTotalCpuNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (auxThreadStats.hasTotalBlockedNanos()) {
            boundStatement.setDouble(i++, auxThreadStats.getTotalBlockedNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (auxThreadStats.hasTotalWaitedNanos()) {
            boundStatement.setDouble(i++, auxThreadStats.getTotalWaitedNanos().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        if (auxThreadStats.hasTotalAllocatedBytes()) {
            boundStatement.setDouble(i++, auxThreadStats.getTotalAllocatedBytes().getValue());
        } else {
            boundStatement.setToNull(i++);
        }
        boundStatement.setInt(i++, adjustedTTL);
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

    private void mergeProfilesInto(String agentRollup, TransactionQuery query, Table profileTable,
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

    private RollupParams getRollupParams(String agentRollup, int rollupLevel, int adjustedTTL)
            throws IOException {
        ImmutableRollupParams.Builder rollupInfo = ImmutableRollupParams.builder()
                .agentRollup(agentRollup)
                .rollupLevel(rollupLevel)
                .adjustedTTL(adjustedTTL);
        AdvancedConfig advancedConfig = configRepository.getAdvancedConfig(agentRollup);
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

    static List<NeedsRollup> getNeedsRollupList(String agentRollup, int rollupLevel,
            List<PreparedStatement> readNeedsRollup, Session session) {
        BoundStatement boundStatement = readNeedsRollup.get(rollupLevel - 1).bind();
        boundStatement.setString(0, agentRollup);
        ResultSet results = session.execute(boundStatement);
        Map<Long, NeedsRollup> needsRollupMap = Maps.newLinkedHashMap();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            UUID uniqueness = row.getUUID(i++);
            Set<String> keys = checkNotNull(row.getSet(i++, String.class));
            NeedsRollup needsRollup = needsRollupMap.get(captureTime);
            if (needsRollup == null) {
                needsRollup = new NeedsRollup(captureTime);
                needsRollupMap.put(captureTime, needsRollup);
            }
            needsRollup.keys.addAll(keys);
            needsRollup.uniquenessKeysForDeletion.add(uniqueness);
        }
        if (needsRollupMap.isEmpty()) {
            return ImmutableList.of();
        }
        List<NeedsRollup> needsRollupList = Lists.newArrayList(needsRollupMap.values());
        // don't roll up the most recent time for each agent since it is likely still being added,
        // this is mostly to avoid rolling up this data twice, but also currently the UI assumes
        // when it finds a 1-min rollup it doesn't check for non-rolled up 1-min aggregates
        needsRollupList.remove(needsRollupList.size() - 1);
        return needsRollupList;
    }

    // it is important that the insert into next gauge_needs_rollup happens after present
    // rollup and before deleting present rollup
    // if insert before present rollup then possible for the next rollup to occur before
    // present rollup has completed
    // if insert after deleting present rollup then possible for error to occur in between
    // and insert would never happen
    static void postRollup(String agentRollup, int rollupLevel, NeedsRollup needsRollup,
            @Nullable Long nextRollupIntervalMillis, List<PreparedStatement> insertNeedsRollup,
            List<PreparedStatement> deleteNeedsRollup, Session session) {
        if (nextRollupIntervalMillis != null) {
            long rollupCaptureTime = Utils.getRollupCaptureTime(needsRollup.getCaptureTime(),
                    nextRollupIntervalMillis);
            BoundStatement boundStatement = insertNeedsRollup.get(rollupLevel).bind();
            int i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setTimestamp(i++, new Date(rollupCaptureTime));
            boundStatement.setUUID(i++, UUIDs.timeBased());
            boundStatement.setSet(i++, needsRollup.getKeys());
            session.execute(boundStatement);
        }
        for (UUID uniqueness : needsRollup.getUniquenessKeysForDeletion()) {
            BoundStatement boundStatement = deleteNeedsRollup.get(rollupLevel - 1).bind();
            int i = 0;
            boundStatement.setString(i++, agentRollup);
            boundStatement.setTimestamp(i++, new Date(needsRollup.getCaptureTime()));
            boundStatement.setUUID(i++, uniqueness);
            session.execute(boundStatement);
        }
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
                    + " capture_time");
        } else {
            sb.append(", primary key ((agent_rollup, transaction_type), capture_time");
        }
        for (String clusterKey : table.clusterKey()) {
            sb.append(", ");
            sb.append(clusterKey);
        }
        sb.append(")) ");
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
        appendColumnNames(sb, table.columns());
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
        appendColumnNames(sb, table.columns());
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

    private static void appendColumnNames(StringBuilder sb, List<Column> columns) {
        boolean addSeparator = false;
        for (Column column : columns) {
            if (addSeparator) {
                sb.append(", ");
            }
            sb.append(column.name());
            addSeparator = true;
        }
    }

    private static @Nullable Aggregate.ThreadStats buildThreadStats(@Nullable Double totalCpuNanos,
            @Nullable Double totalBlockedNanos, @Nullable Double totalWaitedNanos,
            @Nullable Double totalAllocatedBytes) {
        if (totalCpuNanos == null && totalBlockedNanos == null && totalWaitedNanos == null
                && totalAllocatedBytes == null) {
            return null;
        }
        Aggregate.ThreadStats.Builder threadStats = Aggregate.ThreadStats.newBuilder();
        if (totalCpuNanos != null) {
            threadStats.setTotalCpuNanos(OptionalDouble.newBuilder().setValue(totalCpuNanos));
        }
        if (totalBlockedNanos != null) {
            threadStats
                    .setTotalBlockedNanos(OptionalDouble.newBuilder().setValue(totalBlockedNanos));
        }
        if (totalWaitedNanos != null) {
            threadStats.setTotalWaitedNanos(OptionalDouble.newBuilder().setValue(totalWaitedNanos));
        }
        if (totalAllocatedBytes != null) {
            threadStats.setTotalAllocatedBytes(
                    OptionalDouble.newBuilder().setValue(totalAllocatedBytes));
        }
        return threadStats.build();
    }

    private static ByteBuffer toByteBuffer(AbstractMessage message) {
        return ByteBuffer.wrap(message.toByteString().toByteArray());
    }

    @Value.Immutable
    interface Table {
        String partialName();
        List<Column> columns();
        List<String> clusterKey();
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
        int adjustedTTL();
        int maxAggregateQueriesPerType();
        int maxAggregateServiceCallsPerType();
    }

    static class NeedsRollup {

        private final long captureTime;
        private final Set<String> keys = Sets.newHashSet(); // transaction types or gauge names
        private final Set<UUID> uniquenessKeysForDeletion = Sets.newHashSet();

        private NeedsRollup(long captureTime) {
            this.captureTime = captureTime;
        }

        long getCaptureTime() {
            return captureTime;
        }

        Set<String> getKeys() {
            return keys;
        }

        Set<UUID> getUniquenessKeysForDeletion() {
            return uniquenessKeysForDeletion;
        }
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
