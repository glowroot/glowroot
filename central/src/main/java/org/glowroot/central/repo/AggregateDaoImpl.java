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
package org.glowroot.central.repo;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.google.common.base.Strings;
import com.google.common.collect.*;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Ints;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.spotify.futures.CompletableFutures;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.repo.Common.NeedsRollup;
import org.glowroot.central.repo.Common.NeedsRollupFromChildren;
import org.glowroot.central.util.Messages;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common.ConfigDefaults;
import org.glowroot.common.Constants;
import org.glowroot.common.live.ImmutableAggregateQuery;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.*;
import org.glowroot.common.model.*;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.TransactionNameErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionNameSummaryCollector.SummarySortOrder;
import org.glowroot.common.util.*;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.MutableAggregate;
import org.glowroot.common2.repo.MutableThreadStats;
import org.glowroot.common2.repo.MutableTimer;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OldTransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.immutables.value.Value;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.glowroot.common2.repo.CassandraProfile.collector;
import static org.glowroot.common2.repo.CassandraProfile.rollup;

public class AggregateDaoImpl implements AggregateDao {

    @SuppressWarnings("deprecation")
    private static final HashFunction SHA_1 = Hashing.sha1();

    private static final Table summaryTable = ImmutableTable.builder()
            .partialName("summary")
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("total_cpu_nanos", "double"))
            .addColumns(ImmutableColumn.of("total_allocated_bytes", "double"))
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
            .addColumns(ImmutableColumn.of("main_thread_total_cpu_nanos", "double"))
            .addColumns(ImmutableColumn.of("main_thread_total_blocked_nanos", "double"))
            .addColumns(ImmutableColumn.of("main_thread_total_waited_nanos", "double"))
            .addColumns(ImmutableColumn.of("main_thread_total_allocated_bytes", "double"))
            // ideally this would be named aux_thread_root_timer (as there is a single root)
            .addColumns(ImmutableColumn.of("aux_thread_root_timers", "blob"))
            .addColumns(ImmutableColumn.of("aux_thread_total_cpu_nanos", "double"))
            .addColumns(ImmutableColumn.of("aux_thread_total_blocked_nanos", "double"))
            .addColumns(ImmutableColumn.of("aux_thread_total_waited_nanos", "double"))
            .addColumns(ImmutableColumn.of("aux_thread_total_allocated_bytes", "double"))
            // ideally this would be named async_timers (as they are all root)
            .addColumns(ImmutableColumn.of("async_root_timers", "blob"))
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
            .addColumns(ImmutableColumn.of("error_count", "bigint"))
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
    private final ActiveAgentDao activeAgentDao;
    private final TransactionTypeDao transactionTypeDao;
    private final FullQueryTextDao fullQueryTextDao;
    private final ConfigRepositoryImpl configRepository;
    private final Executor asyncExecutor;
    private final Clock clock;

    // list index is rollupLevel
    private final Map<Table, List<PreparedStatement>> insertOverallPS;
    private final Map<Table, List<PreparedStatement>> insertTransactionPS;
    private final Map<Table, List<PreparedStatement>> readOverallPS;
    private final Map<Table, List<PreparedStatement>> readOverallForRollupPS;
    private final Map<Table, PreparedStatement> readOverallForRollupFromChildPS;
    private final Map<Table, List<PreparedStatement>> readTransactionPS;
    private final Map<Table, List<PreparedStatement>> readTransactionForRollupPS;
    private final Map<Table, PreparedStatement> readTransactionForRollupFromChildPS;

    private final List<PreparedStatement> existsMainThreadProfileOverallPS;
    private final List<PreparedStatement> existsMainThreadProfileTransactionPS;
    private final List<PreparedStatement> existsAuxThreadProfileOverallPS;
    private final List<PreparedStatement> existsAuxThreadProfileTransactionPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    private final PreparedStatement insertNeedsRollupFromChild;
    private final PreparedStatement readNeedsRollupFromChild;
    private final PreparedStatement deleteNeedsRollupFromChild;

    private final ImmutableList<Table> allTables;

    AggregateDaoImpl(Session session, ActiveAgentDao activeAgentDao,
                     TransactionTypeDao transactionTypeDao, FullQueryTextDao fullQueryTextDao,
                     ConfigRepositoryImpl configRepository, Executor asyncExecutor, int cassandraGcGraceSeconds, Clock clock)
            throws Exception {
        this.session = session;
        this.activeAgentDao = activeAgentDao;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;
        this.configRepository = configRepository;
        this.asyncExecutor = asyncExecutor;
        this.clock = clock;

        int count = configRepository.getRollupConfigs().size();
        List<Integer> rollupExpirationHours =
                configRepository.getCentralStorageConfig().rollupExpirationHours();
        List<Integer> queryAndServiceCallRollupExpirationHours =
                configRepository.getCentralStorageConfig()
                        .queryAndServiceCallRollupExpirationHours();
        List<Integer> profileRollupExpirationHours =
                configRepository.getCentralStorageConfig().profileRollupExpirationHours();

        allTables = ImmutableList.of(summaryTable, errorSummaryTable, overviewTable,
                histogramTable, throughputTable, queryTable, serviceCallTable,
                mainThreadProfileTable, auxThreadProfileTable);
        Map<Table, List<PreparedStatement>> insertOverallMap = new HashMap<>();
        Map<Table, List<PreparedStatement>> insertTransactionMap = new HashMap<>();
        Map<Table, List<PreparedStatement>> readOverallMap = new HashMap<>();
        Map<Table, List<PreparedStatement>> readOverallForRollupMap = new HashMap<>();
        Map<Table, PreparedStatement> readOverallForRollupFromChildMap = new HashMap<>();
        Map<Table, List<PreparedStatement>> readTransactionMap = new HashMap<>();
        Map<Table, List<PreparedStatement>> readTransactionForRollupMap = new HashMap<>();
        Map<Table, PreparedStatement> readTransactionForRollupFromChildMap = new HashMap<>();
        for (Table table : allTables) {
            List<PreparedStatement> insertOverallList = new ArrayList<>();
            List<PreparedStatement> insertTransactionList = new ArrayList<>();
            List<PreparedStatement> readOverallList = new ArrayList<>();
            List<PreparedStatement> readOverallForRollupList = new ArrayList<>();
            List<PreparedStatement> readTransactionList = new ArrayList<>();
            List<PreparedStatement> readTransactionForRollupList = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int expirationHours;
                if (table.partialName().equals("query")
                        || table.partialName().equals("service_call")) {
                    expirationHours = queryAndServiceCallRollupExpirationHours.get(i);
                } else if (table.partialName().equals("main_thread_profile")
                        || table.partialName().equals("aux_thread_profile")) {
                    expirationHours = profileRollupExpirationHours.get(i);
                } else {
                    expirationHours = rollupExpirationHours.get(i);
                }
                if (table.summary()) {
                    session.createTableWithTWCS(createSummaryTableQuery(table, false, i),
                            expirationHours);
                    session.createTableWithTWCS(createSummaryTableQuery(table, true, i),
                            expirationHours);
                    insertOverallList.add(session.prepare(insertSummaryPS(table, false, i)));
                    insertTransactionList.add(session.prepare(insertSummaryPS(table, true, i)));
                    readOverallList.add(session.prepare(readSummaryPS(table, false, i)));
                    readOverallForRollupList
                            .add(session.prepare(readSummaryForRollupPS(table, false, i)));
                    readTransactionList.add(session.prepare(readSummaryPS(table, true, i)));
                    readTransactionForRollupList
                            .add(session.prepare(readSummaryForRollupPS(table, true, i)));
                } else {
                    session.createTableWithTWCS(createTableQuery(table, false, i), expirationHours);
                    session.createTableWithTWCS(createTableQuery(table, true, i), expirationHours);
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
            if (table.summary()) {
                readOverallForRollupFromChildMap.put(table,
                        session.prepare(readSummaryForRollupFromChildPS(table, false, 0)));
            } else {
                readOverallForRollupFromChildMap.put(table,
                        session.prepare(readForRollupFromChildPS(table, false, 0)));
            }
            readTransactionMap.put(table, ImmutableList.copyOf(readTransactionList));
            readTransactionForRollupMap.put(table,
                    ImmutableList.copyOf(readTransactionForRollupList));
            if (table.summary()) {
                readTransactionForRollupFromChildMap.put(table,
                        session.prepare(readSummaryForRollupFromChildPS(table, true, 0)));
            } else {
                readTransactionForRollupFromChildMap.put(table,
                        session.prepare(readForRollupFromChildPS(table, true, 0)));
            }
        }
        this.insertOverallPS = ImmutableMap.copyOf(insertOverallMap);
        this.insertTransactionPS = ImmutableMap.copyOf(insertTransactionMap);
        this.readOverallPS = ImmutableMap.copyOf(readOverallMap);
        this.readOverallForRollupPS = ImmutableMap.copyOf(readOverallForRollupMap);
        this.readOverallForRollupFromChildPS =
                ImmutableMap.copyOf(readOverallForRollupFromChildMap);
        this.readTransactionPS = ImmutableMap.copyOf(readTransactionMap);
        this.readTransactionForRollupPS = ImmutableMap.copyOf(readTransactionForRollupMap);
        this.readTransactionForRollupFromChildPS =
                ImmutableMap.copyOf(readTransactionForRollupFromChildMap);

        List<PreparedStatement> existsMainThreadProfileOverallPS = new ArrayList<>();
        List<PreparedStatement> existsMainThreadProfileTransactionPS = new ArrayList<>();
        List<PreparedStatement> existsAuxThreadProfileOverallPS = new ArrayList<>();
        List<PreparedStatement> existsAuxThreadProfileTransactionPS = new ArrayList<>();
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

        List<PreparedStatement> insertNeedsRollup = new ArrayList<>();
        List<PreparedStatement> readNeedsRollup = new ArrayList<>();
        List<PreparedStatement> deleteNeedsRollup = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            session.createTableWithLCS("create table if not exists aggregate_needs_rollup_" + i
                    + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                    + " transaction_types set<varchar>, primary key (agent_rollup, capture_time,"
                    + " uniqueness)) with gc_grace_seconds = " + cassandraGcGraceSeconds, true);
            // TTL is used to prevent non-idempotent rolling up of partially expired aggregates
            // (e.g. "needs rollup" record resurrecting due to small gc_grace_seconds)
            insertNeedsRollup.add(session.prepare("insert into aggregate_needs_rollup_" + i
                    + " (agent_rollup, capture_time, uniqueness, transaction_types) values"
                    + " (?, ?, ?, ?) using TTL ?"));
            readNeedsRollup.add(session.prepare("select capture_time, uniqueness, transaction_types"
                    + " from aggregate_needs_rollup_" + i + " where agent_rollup = ?"));
            deleteNeedsRollup.add(session.prepare("delete from aggregate_needs_rollup_" + i
                    + " where agent_rollup = ? and capture_time = ? and uniqueness = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;

        session.createTableWithLCS("create table if not exists aggregate_needs_rollup_from_child"
                + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                + " child_agent_rollup varchar, transaction_types set<varchar>, primary key"
                + " (agent_rollup, capture_time, uniqueness)) with gc_grace_seconds = "
                + cassandraGcGraceSeconds, true);
        // TTL is used to prevent non-idempotent rolling up of partially expired aggregates
        // (e.g. "needs rollup" record resurrecting due to small gc_grace_seconds)
        insertNeedsRollupFromChild = session.prepare("insert into aggregate_needs_rollup_from_child"
                + " (agent_rollup, capture_time, uniqueness, child_agent_rollup, transaction_types)"
                + " values (?, ?, ?, ?, ?) using TTL ?");
        readNeedsRollupFromChild = session.prepare("select capture_time, uniqueness,"
                + " child_agent_rollup, transaction_types from aggregate_needs_rollup_from_child"
                + " where agent_rollup = ?");
        deleteNeedsRollupFromChild = session.prepare("delete from aggregate_needs_rollup_from_child"
                + " where agent_rollup = ? and capture_time = ? and uniqueness = ?");
    }

    @CheckReturnValue
    @Override
    public CompletableFuture<?> store(String agentId, long captureTime,
                                      List<OldAggregatesByType> aggregatesByTypeList,
                                      List<Aggregate.SharedQueryText> initialSharedQueryTexts) {
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentId);
        return store(agentId, agentRollupIds, agentId, agentRollupIds, captureTime, aggregatesByTypeList,
                initialSharedQueryTexts);
    }

    @CheckReturnValue
    public CompletableFuture<?> store(String agentId, List<String> agentRollupIds,
                                      String agentIdForMeta, List<String> agentRollupIdsForMeta, long captureTime,
                                      List<OldAggregatesByType> aggregatesByTypeList,
                                      List<Aggregate.SharedQueryText> initialSharedQueryTexts) {
        if (aggregatesByTypeList.isEmpty()) {
            return activeAgentDao.insert(agentIdForMeta, captureTime).thenCompose(CompletableFutures::allAsList).toCompletableFuture();
        }
        TTL adjustedTTL = getAdjustedTTL(getTTLs().get(0), captureTime, clock);
        List<CompletableFuture<?>> completableFutures = new ArrayList<>();
        List<Aggregate.SharedQueryText> sharedQueryTexts = new ArrayList<>();
        for (Aggregate.SharedQueryText sharedQueryText : initialSharedQueryTexts) {
            String fullTextSha1 = sharedQueryText.getFullTextSha1();
            if (fullTextSha1.isEmpty()) {
                String fullText = sharedQueryText.getFullText();
                if (fullText.length() > Constants.AGGREGATE_QUERY_TEXT_TRUNCATE) {
                    // relying on agent side to rate limit (re-)sending the same full text
                    fullTextSha1 = SHA_1.hashString(fullText, UTF_8).toString();
                    completableFutures.addAll(fullQueryTextDao.store(agentRollupIds, fullTextSha1, fullText));
                    sharedQueryText = Aggregate.SharedQueryText.newBuilder()
                            .setTruncatedText(fullText.substring(0,
                                    Constants.AGGREGATE_QUERY_TEXT_TRUNCATE))
                            .setFullTextSha1(fullTextSha1)
                            .build();
                }
            }
            sharedQueryTexts.add(sharedQueryText);
        }

        // wait for success before proceeding in order to ensure cannot end up with orphaned
        // fullTextSha1
        return CompletableFutures.allAsList(completableFutures).thenCompose(ignored -> {
            List<CompletableFuture<?>> futures = new ArrayList<>();

            for (OldAggregatesByType aggregatesByType : aggregatesByTypeList) {
                String transactionType = aggregatesByType.getTransactionType();
                Aggregate overallAggregate = aggregatesByType.getOverallAggregate();
                List<CompletableFuture<?>> futures1 = new ArrayList<>(
                        storeOverallAggregate(agentId, transactionType, captureTime,
                                overallAggregate, sharedQueryTexts, adjustedTTL));
                for (OldTransactionAggregate transactionAggregate : aggregatesByType
                        .getTransactionAggregateList()) {
                    futures1.addAll(storeTransactionAggregate(agentId, transactionType,
                            transactionAggregate.getTransactionName(), captureTime,
                            transactionAggregate.getAggregate(), sharedQueryTexts, adjustedTTL));
                }
                // wait for success before proceeding in order to ensure cannot end up with
                // "no overview table records found" during a transactionName rollup, since
                // transactionName rollups are based on finding transactionName in summary table
                futures.add(CompletableFutures.allAsList(futures1).thenCompose(ignoredResult -> {
                    List<CompletableFuture<?>> futuresInner = new ArrayList<>();
                    for (OldTransactionAggregate transactionAggregate : aggregatesByType
                            .getTransactionAggregateList()) {
                        futuresInner.addAll(storeTransactionNameSummary(agentId, transactionType,
                                transactionAggregate.getTransactionName(), captureTime,
                                transactionAggregate.getAggregate(), adjustedTTL));
                    }
                    futuresInner.addAll(transactionTypeDao.store(agentRollupIdsForMeta, transactionType));
                    return CompletableFutures.allAsList(futuresInner);
                }));
            }
            return CompletableFutures.allAsList(futures)
                    // wait for success before inserting "needs rollup" records
                    .thenCompose(ignoredResult -> activeAgentDao.insert(agentIdForMeta, captureTime))
                    .thenCompose(CompletableFutures::allAsList).toCompletableFuture();
        }).thenCompose(e -> {
            List<CompletableFuture<?>> futures = new ArrayList<>();

            List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
            // TODO report checker framework issue that occurs without this suppression
            @SuppressWarnings("assignment.type.incompatible")
            Set<String> transactionTypes = aggregatesByTypeList.stream()
                    .map(OldAggregatesByType::getTransactionType).collect(Collectors.toSet());

            int needsRollupAdjustedTTL =
                    Common.getNeedsRollupAdjustedTTL(adjustedTTL.generalTTL(), rollupConfigs);
            if (agentRollupIds.size() > 1) {
                int i = 0;
                BoundStatement boundStatement = insertNeedsRollupFromChild.bind()
                        .setString(i++, agentRollupIds.get(1))
                        .setInstant(i++, Instant.ofEpochMilli(captureTime))
                        .setUuid(i++, Uuids.timeBased())
                        .setString(i++, agentId)
                        .setSet(i++, transactionTypes, String.class)
                        .setInt(i++, needsRollupAdjustedTTL);
                futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
            }
            // insert into aggregate_needs_rollup_1
            long intervalMillis = rollupConfigs.get(1).intervalMillis();
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime, intervalMillis);
            int i = 0;
            BoundStatement boundStatement = insertNeedsRollup.get(0).bind()
                    .setString(i++, agentId)
                    .setInstant(i++, Instant.ofEpochMilli(rollupCaptureTime))
                    .setUuid(i++, Uuids.timeBased())
                    .setSet(i++, transactionTypes, String.class)
                    .setInt(i++, needsRollupAdjustedTTL);
            futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
            return CompletableFutures.allAsList(futures);
        });
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<?> mergeOverallSummaryInto(String agentRollupId, SummaryQuery query,
                                                      OverallSummaryCollector collector, CassandraProfile profile) {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        Function<AsyncResultSet, CompletableFuture<Void>> compute = new Function<AsyncResultSet, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    // results are ordered by capture time so Math.max() is not needed here
                    long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                    double totalDurationNanos = row.getDouble(i++);
                    double totalCpuNanos = row.getDouble(i++);
                    double totalAllocatedBytes = row.getDouble(i++);
                    long transactionCount = row.getLong(i++);
                    collector.mergeSummary(totalDurationNanos, totalCpuNanos, totalAllocatedBytes, transactionCount,
                            captureTime);
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return executeQuery(agentRollupId, query, summaryTable, profile).thenCompose(compute);
    }

    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionNameSummaryCollector
    //
    // query.from() is non-inclusive
    @Override
    public CompletionStage<?> mergeTransactionNameSummariesInto(String agentRollupId, SummaryQuery query,
                                                                SummarySortOrder sortOrder, int limit, TransactionNameSummaryCollector collector, CassandraProfile profile) {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement =
                checkNotNull(readTransactionPS.get(summaryTable)).get(query.rollupLevel()).bind();
        boundStatement = bindQuery(boundStatement, agentRollupId, query);
        Function<AsyncResultSet, CompletableFuture<Void>> compute = new Function<AsyncResultSet, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                    String transactionName = checkNotNull(row.getString(i++));
                    double totalDurationNanos = row.getDouble(i++);
                    double totalCpuNanos = row.getDouble(i++);
                    double totalAllocatedBytes = row.getDouble(i++);
                    long transactionCount = row.getLong(i++);
                    collector.collect(transactionName, totalDurationNanos, totalCpuNanos, totalAllocatedBytes, transactionCount,
                            captureTime);
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return session.readAsync(boundStatement, profile).thenCompose(compute);
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<?> mergeOverallErrorSummaryInto(String agentRollupId, SummaryQuery query,
                                                           OverallErrorSummaryCollector collector, CassandraProfile profile) {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        Function<AsyncResultSet, CompletableFuture<Void>> compute = new Function<AsyncResultSet, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    // results are ordered by capture time so Math.max() is not needed here
                    long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                    long errorCount = row.getLong(i++);
                    long transactionCount = row.getLong(i++);
                    collector.mergeErrorSummary(errorCount, transactionCount, captureTime);
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return executeQuery(agentRollupId, query, errorSummaryTable, profile).thenCompose(compute);
    }

    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionNameErrorSummaryCollector
    //
    // query.from() is non-inclusive
    @Override
    public CompletionStage<?> mergeTransactionNameErrorSummariesInto(String agentRollupId, SummaryQuery query,
                                                                     ErrorSummarySortOrder sortOrder, int limit,
                                                                     TransactionNameErrorSummaryCollector collector, CassandraProfile profile) {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement = checkNotNull(readTransactionPS.get(errorSummaryTable))
                .get(query.rollupLevel()).bind();
        boundStatement = bindQuery(boundStatement, agentRollupId, query);
        Function<AsyncResultSet, CompletableFuture<Void>> compute = new Function<AsyncResultSet, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                    String transactionName = checkNotNull(row.getString(i++));
                    long errorCount = row.getLong(i++);
                    long transactionCount = row.getLong(i++);
                    collector.collect(transactionName, errorCount, transactionCount, captureTime);
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return session.readAsync(boundStatement, profile).thenCompose(compute);
    }

    // query.from() is INCLUSIVE
    @Override
    public CompletionStage<List<OverviewAggregate>> readOverviewAggregates(String agentRollupId,
                                                                           AggregateQuery query, CassandraProfile profile) {
        List<OverviewAggregate> overviewAggregates = new ArrayList<>();
        Function<AsyncResultSet, CompletableFuture<List<OverviewAggregate>>> compute = new Function<AsyncResultSet, CompletableFuture<List<OverviewAggregate>>>() {
            @Override
            public CompletableFuture<List<OverviewAggregate>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                    double totalDurationNanos = row.getDouble(i++);
                    long transactionCount = row.getLong(i++);
                    boolean asyncTransactions = row.getBoolean(i++);
                    List<Aggregate.Timer> mainThreadRootTimers =
                            Messages.parseDelimitedFrom(row.getByteBuffer(i++), Aggregate.Timer.parser());
                    Aggregate.ThreadStats mainThreadStats = Aggregate.ThreadStats.newBuilder()
                            .setTotalCpuNanos(getNextThreadStat(row, i++))
                            .setTotalBlockedNanos(getNextThreadStat(row, i++))
                            .setTotalWaitedNanos(getNextThreadStat(row, i++))
                            .setTotalAllocatedBytes(getNextThreadStat(row, i++))
                            .build();
                    // reading delimited singleton list for backwards compatibility with data written
                    // prior to 0.12.0
                    List<Aggregate.Timer> list =
                            Messages.parseDelimitedFrom(row.getByteBuffer(i++), Aggregate.Timer.parser());
                    Aggregate.Timer auxThreadRootTimer = list.isEmpty() ? null : list.get(0);
                    Aggregate.ThreadStats auxThreadStats;
                    if (auxThreadRootTimer == null) {
                        auxThreadStats = null;
                        i += 4;
                    } else {
                        auxThreadStats = Aggregate.ThreadStats.newBuilder()
                                .setTotalCpuNanos(getNextThreadStat(row, i++))
                                .setTotalBlockedNanos(getNextThreadStat(row, i++))
                                .setTotalWaitedNanos(getNextThreadStat(row, i++))
                                .setTotalAllocatedBytes(getNextThreadStat(row, i++))
                                .build();
                    }
                    List<Aggregate.Timer> asyncTimers =
                            Messages.parseDelimitedFrom(row.getByteBuffer(i++), Aggregate.Timer.parser());
                    ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                            .captureTime(captureTime)
                            .totalDurationNanos(totalDurationNanos)
                            .transactionCount(transactionCount)
                            .asyncTransactions(asyncTransactions)
                            .addAllMainThreadRootTimers(mainThreadRootTimers)
                            .mainThreadStats(mainThreadStats)
                            .auxThreadRootTimer(auxThreadRootTimer)
                            .auxThreadStats(auxThreadStats)
                            .addAllAsyncTimers(asyncTimers);
                    overviewAggregates.add(builder.build());
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(overviewAggregates);
            }
        };
        return executeQuery(agentRollupId, query, overviewTable, profile).thenCompose(compute);
    }

    // query.from() is INCLUSIVE
    @Override
    public CompletionStage<List<PercentileAggregate>> readPercentileAggregates(String agentRollupId,
                                                                               AggregateQuery query, CassandraProfile profile) {
        List<PercentileAggregate> percentileAggregates = new ArrayList<>();
        Function<AsyncResultSet, CompletableFuture<List<PercentileAggregate>>> compute = new Function<AsyncResultSet, CompletableFuture<List<PercentileAggregate>>>() {
            @Override
            public CompletableFuture<List<PercentileAggregate>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                    double totalDurationNanos = row.getDouble(i++);
                    long transactionCount = row.getLong(i++);
                    ByteBuffer bytes = checkNotNull(row.getByteBuffer(i++));
                    Aggregate.Histogram durationNanosHistogram = null;
                    try {
                        durationNanosHistogram = Aggregate.Histogram.parseFrom(bytes);
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException(e);
                    }
                    percentileAggregates.add(ImmutablePercentileAggregate.builder()
                            .captureTime(captureTime)
                            .totalDurationNanos(totalDurationNanos)
                            .transactionCount(transactionCount)
                            .durationNanosHistogram(durationNanosHistogram)
                            .build());
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(percentileAggregates);
            }
        };
        return executeQuery(agentRollupId, query, histogramTable, profile).thenCompose(compute);
    }

    // query.from() is INCLUSIVE
    @Override
    public CompletionStage<List<ThroughputAggregate>> readThroughputAggregates(String agentRollupId,
                                                                               AggregateQuery query, CassandraProfile profile) {
        List<ThroughputAggregate> throughputAggregates = new ArrayList<>();
        Function<AsyncResultSet, CompletableFuture<List<ThroughputAggregate>>> compute = new Function<AsyncResultSet, CompletableFuture<List<ThroughputAggregate>>>() {
            @Override
            public CompletableFuture<List<ThroughputAggregate>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                    long transactionCount = row.getLong(i++);
                    boolean hasErrorCount = !row.isNull(i);
                    long errorCount = row.getLong(i++);
                    throughputAggregates.add(ImmutableThroughputAggregate.builder()
                            .captureTime(captureTime)
                            .transactionCount(transactionCount)
                            .errorCount(hasErrorCount ? errorCount : null)
                            .build());
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(throughputAggregates);
            }
        };
        return executeQuery(agentRollupId, query, throughputTable, profile).thenCompose(compute);
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<?> mergeQueriesInto(String agentRollupId, AggregateQuery query,
                                               QueryCollector collector, CassandraProfile profile) {
        Function<AsyncResultSet, CompletableFuture<Void>> compute = new Function<AsyncResultSet, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(AsyncResultSet results) {
                long captureTime = Long.MIN_VALUE;
                for (Row row : results.currentPage()) {
                    int i = 0;
                    captureTime = Math.max(captureTime, checkNotNull(row.getInstant(i++)).toEpochMilli());
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
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return executeQuery(agentRollupId, query, queryTable, profile).thenCompose(compute);
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<?> mergeServiceCallsInto(String agentRollupId, AggregateQuery query,
                                                    ServiceCallCollector collector, CassandraProfile profile) {
        Function<AsyncResultSet, CompletableFuture<Void>> compute = new Function<AsyncResultSet, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(AsyncResultSet results) {
                long captureTime = Long.MIN_VALUE;
                for (Row row : results.currentPage()) {
                    int i = 0;
                    captureTime = Math.max(captureTime, checkNotNull(row.getInstant(i++)).toEpochMilli());
                    String serviceCallType = checkNotNull(row.getString(i++));
                    String serviceCallText = checkNotNull(row.getString(i++));
                    double totalDurationNanos = row.getDouble(i++);
                    long executionCount = row.getLong(i++);
                    collector.mergeServiceCall(serviceCallType, serviceCallText, totalDurationNanos,
                            executionCount);
                    collector.updateLastCaptureTime(captureTime);
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return executeQuery(agentRollupId, query, serviceCallTable, profile).thenCompose(compute);
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<?> mergeMainThreadProfilesInto(String agentRollupId, AggregateQuery query,
                                                          ProfileCollector collector, CassandraProfile profile) {
        return mergeProfilesInto(agentRollupId, query, mainThreadProfileTable, collector, profile);
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<?> mergeAuxThreadProfilesInto(String agentRollupId, AggregateQuery query,
                                                         ProfileCollector collector, CassandraProfile profile) {
        return mergeProfilesInto(agentRollupId, query, auxThreadProfileTable, collector, profile);
    }

    @Override
    public CompletionStage<String> readFullQueryText(String agentRollupId, String fullQueryTextSha1, CassandraProfile profile) {
        return fullQueryTextDao.getFullText(agentRollupId, fullQueryTextSha1, profile);
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<Boolean> hasMainThreadProfile(String agentRollupId, AggregateQuery query, CassandraProfile profile) {
        BoundStatement boundStatement = query.transactionName() == null
                ? existsMainThreadProfileOverallPS.get(query.rollupLevel()).bind()
                : existsMainThreadProfileTransactionPS.get(query.rollupLevel()).bind();
        boundStatement = bindQuery(boundStatement, agentRollupId, query);
        return session.readAsync(boundStatement, profile).thenApply(results -> results.one() != null);
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<Boolean> hasAuxThreadProfile(String agentRollupId, AggregateQuery query, CassandraProfile profile) {
        BoundStatement boundStatement = query.transactionName() == null
                ? existsAuxThreadProfileOverallPS.get(query.rollupLevel()).bind()
                : existsAuxThreadProfileTransactionPS.get(query.rollupLevel()).bind();
        boundStatement = bindQuery(boundStatement, agentRollupId, query);
        return session.readAsync(boundStatement, profile).thenApply(results -> results.one() != null);
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<Boolean> shouldHaveQueries(String agentRollupId, AggregateQuery query) {
        // TODO (this is only used for displaying data expired message)
        return CompletableFuture.completedFuture(false);
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<Boolean> shouldHaveServiceCalls(String agentRollupId, AggregateQuery query) {
        // TODO (this is only used for displaying data expired message)
        return CompletableFuture.completedFuture(false);
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<Boolean> shouldHaveMainThreadProfile(String agentRollupId, AggregateQuery query) {
        // TODO (this is only used for displaying data expired message)
        return CompletableFuture.completedFuture(false);
    }

    // query.from() is non-inclusive
    @Override
    public CompletionStage<Boolean> shouldHaveAuxThreadProfile(String agentRollupId, AggregateQuery query) {
        // TODO (this is only used for displaying data expired message)
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletionStage<?> rollup(String agentRollupId) {
        return rollup(agentRollupId, agentRollupId, AgentRollupIds.getParent(agentRollupId),
                !agentRollupId.endsWith("::"));
    }

    public CompletionStage<?> rollup(String agentRollupId, String agentRollupIdForMeta,
                                     @Nullable String parentAgentRollupId, boolean leaf) {
        List<TTL> ttls = getTTLs();
        CompletionStage<?> future = CompletableFuture.completedFuture(null);
        if (!leaf) {
            future = rollupFromChildren(agentRollupId, agentRollupIdForMeta, parentAgentRollupId,
                    ttls.get(0));
        }
        return future.thenCompose(ignored -> {

            List<CompletionStage<?>> futures = new ArrayList<>();
            int rollupLevel = 1;
            while (rollupLevel < configRepository.getRollupConfigs().size()) {
                TTL ttl = ttls.get(rollupLevel);
                futures.add(rollup(agentRollupId, agentRollupIdForMeta, rollupLevel, ttl));
                rollupLevel++;
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
        });
    }

    @Override
    @OnlyUsedByTests
    public void truncateAll() throws Exception {
        for (Table table : allTables) {
            for (int i = 0; i < configRepository.getRollupConfigs().size(); i++) {
                session.updateSchemaWithRetry(
                        "truncate " + getTableName(table.partialName(), false, i));
                session.updateSchemaWithRetry(
                        "truncate " + getTableName(table.partialName(), true, i));
            }
        }
        for (int i = 1; i < configRepository.getRollupConfigs().size(); i++) {
            session.updateSchemaWithRetry("truncate aggregate_needs_rollup_" + i);
        }
        session.updateSchemaWithRetry("truncate aggregate_needs_rollup_from_child");
    }

    private CompletionStage<?> rollupFromChildren(String agentRollupId, String agentRollupIdForMeta,
                                                  @Nullable String parentAgentRollupId, TTL ttl) {
        final int rollupLevel = 0;
        return Common.getNeedsRollupFromChildrenList(agentRollupId, readNeedsRollupFromChild, session, rollup)
                .thenCompose(needsRollupFromChildrenList -> {

                    List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
                    long nextRollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
                    int maxIndexNeedsRollupFromChildrenList = needsRollupFromChildrenList.size();

                    Function<Integer, CompletionStage<?>> lambda = new Function<Integer, CompletionStage<?>>() {
                        @Override
                        public CompletionStage<?> apply(Integer indexNeedsRollupFromChildrenList) {
                            if (indexNeedsRollupFromChildrenList >= maxIndexNeedsRollupFromChildrenList) {
                                return CompletableFuture.completedFuture(null);
                            }
                            NeedsRollupFromChildren needsRollupFromChildren = needsRollupFromChildrenList.get(indexNeedsRollupFromChildrenList);
                            long captureTime = needsRollupFromChildren.getCaptureTime();
                            TTL adjustedTTL = getAdjustedTTL(ttl, captureTime, clock);

                            return getRollupParams(agentRollupId, agentRollupIdForMeta, rollupLevel, adjustedTTL).thenCompose(rollupParams -> {
                                List<CompletionStage<?>> futures = new ArrayList<>();
                                for (Map.Entry<String, Collection<String>> entry : needsRollupFromChildren.getKeys()
                                        .asMap()
                                        .entrySet()) {
                                    String transactionType = entry.getKey();
                                    Collection<String> childAgentRollupIds = entry.getValue();
                                    futures.addAll(rollupOneFromChildren(rollupParams, transactionType,
                                            childAgentRollupIds, captureTime));
                                }
                                return CompletableFutures.allAsList(futures);
                            }).thenCompose(ignore -> {
                                int needsRollupAdjustedTTL =
                                        Common.getNeedsRollupAdjustedTTL(adjustedTTL.generalTTL(), rollupConfigs);
                                CompletionStage<?> starting = CompletableFuture.completedFuture(null);
                                if (parentAgentRollupId != null) {
                                    // insert needs to happen first before call to postRollup(), see method-level
                                    // comment on postRollup
                                    starting = Common.insertNeedsRollupFromChild(agentRollupId, parentAgentRollupId,
                                            insertNeedsRollupFromChild, needsRollupFromChildren, captureTime,
                                            needsRollupAdjustedTTL, session, rollup);
                                }
                                return starting.thenCompose(ignored -> {
                                    return Common.postRollup(agentRollupId, needsRollupFromChildren.getCaptureTime(),
                                            needsRollupFromChildren.getKeys().keySet(),
                                            needsRollupFromChildren.getUniquenessKeysForDeletion(),
                                            nextRollupIntervalMillis, insertNeedsRollup.get(rollupLevel),
                                            deleteNeedsRollupFromChild, needsRollupAdjustedTTL, session, rollup);
                                });
                            }).thenCompose(ignored2 -> apply(indexNeedsRollupFromChildrenList + 1));
                        }
                    };

                    return lambda.apply(0);
                });
    }

    private CompletionStage<?> rollup(String agentRollupId, String agentRollupIdForMeta, int rollupLevel, TTL ttl) {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long rollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
        return Common.getNeedsRollupList(agentRollupId,
                rollupLevel, rollupIntervalMillis, readNeedsRollup, session, clock, rollup).thenCompose(needsRollupCollection -> {
            List<NeedsRollup> needsRollupList = new ArrayList<>(needsRollupCollection);
            Long nextRollupIntervalMillis = null;
            if (rollupLevel + 1 < rollupConfigs.size()) {
                nextRollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
            }
            final Long finalNextRollupIntervalMillis = nextRollupIntervalMillis;
            int maxIndexNeedsRollupList = needsRollupList.size();

            Function<Integer, CompletionStage<?>> lambda = new Function<Integer, CompletionStage<?>>() {
                @Override
                public CompletionStage<?> apply(Integer indexNeedsRollupList) {
                    if (indexNeedsRollupList >= maxIndexNeedsRollupList) {
                        return CompletableFuture.completedFuture(null);
                    }
                    NeedsRollup needsRollup = needsRollupList.get(indexNeedsRollupList);
                    long captureTime = needsRollup.getCaptureTime();
                    TTL adjustedTTL = getAdjustedTTL(ttl, captureTime, clock);
                    int needsRollupAdjustedTTL =
                            Common.getNeedsRollupAdjustedTTL(adjustedTTL.generalTTL(), rollupConfigs);

                    return getRollupParams(agentRollupId, agentRollupIdForMeta, rollupLevel, adjustedTTL)
                            .thenCompose(rollupParams -> {
                                long from = captureTime - rollupIntervalMillis;
                                Set<String> transactionTypes = needsRollup.getKeys();
                                List<CompletionStage<?>> futures = new ArrayList<>();
                                for (String transactionType : transactionTypes) {
                                    futures.addAll(rollupOne(rollupParams, transactionType, from, captureTime));
                                }
                                if (futures.isEmpty()) {
                                    // no rollups occurred, warning already logged inside rollupOne() above
                                    // this can happen there is an old "needs rollup" record that was created prior to
                                    // TTL was introduced in 0.9.6, and when the "last needs rollup" record wasn't
                                    // processed (also prior to 0.9.6), and when the corresponding old data has expired
                                    return Common.postRollup(agentRollupId, needsRollup.getCaptureTime(), transactionTypes,
                                            needsRollup.getUniquenessKeysForDeletion(), null, null,
                                            deleteNeedsRollup.get(rollupLevel - 1), -1, session, rollup).thenApply(ignored -> indexNeedsRollupList + 1);
                                }
                                // wait for above async work to ensure rollup complete before proceeding
                                return CompletableFutures.allAsList(futures).thenCompose((ignored) -> {
                                    PreparedStatement insertNeedsRollup = finalNextRollupIntervalMillis == null ? null
                                            : AggregateDaoImpl.this.insertNeedsRollup.get(rollupLevel);
                                    PreparedStatement deleteNeedsRollup = AggregateDaoImpl.this.deleteNeedsRollup.get(rollupLevel - 1);
                                    return Common.postRollup(agentRollupId, needsRollup.getCaptureTime(), transactionTypes,
                                            needsRollup.getUniquenessKeysForDeletion(), finalNextRollupIntervalMillis,
                                            insertNeedsRollup, deleteNeedsRollup, needsRollupAdjustedTTL, session, rollup).thenApply(ignored2 -> indexNeedsRollupList + 1);
                                });
                            }).thenCompose(this::apply);
                }
            };

            return lambda.apply(0);
        });
    }

    private List<CompletionStage<?>> rollupOneFromChildren(RollupParams rollup, String transactionType,
                                                           Collection<String> childAgentRollupIds, long captureTime) {

        ImmutableAggregateQuery query = ImmutableAggregateQuery.builder()
                .transactionType(transactionType)
                .from(captureTime)
                .to(captureTime)
                .rollupLevel(rollup.rollupLevel()) // rolling up from same level (which is always 0)
                .build();
        List<CompletionStage<?>> futures = new ArrayList<>();

        futures.add(rollupOverallSummaryFromChildren(rollup, query, childAgentRollupIds));
        futures.add(rollupErrorSummaryFromChildren(rollup, query, childAgentRollupIds));

        // key is transaction name, value child agent rollup id
        Multimap<String, String> transactionNames = ArrayListMultimap.create();
        futures.add(rollupTransactionSummaryFromChildren(rollup, query, childAgentRollupIds,
                transactionNames));
        futures.add(rollupTransactionErrorSummaryFromChildren(rollup, query, childAgentRollupIds));

        ScratchBuffer scratchBuffer = new ScratchBuffer();
        futures.addAll(
                rollupOtherPartsFromChildren(rollup, query, childAgentRollupIds, scratchBuffer));

        for (Map.Entry<String, Collection<String>> entry : transactionNames.asMap().entrySet()) {
            futures.addAll(rollupOtherPartsFromChildren(rollup,
                    query.withTransactionName(entry.getKey()), entry.getValue(), scratchBuffer));
        }
        return futures;
    }

    private List<CompletionStage<?>> rollupOne(RollupParams rollup, String transactionType, long from,
                                               long to) {

        ImmutableAggregateQuery query = ImmutableAggregateQuery.builder()
                .transactionType(transactionType)
                .from(from)
                .to(to)
                .rollupLevel(rollup.rollupLevel() - 1)
                .build();
        List<CompletionStage<?>> futures = new ArrayList<>();

        futures.add(rollupOverallSummary(rollup, query));
        futures.add(rollupErrorSummary(rollup, query));

        Set<String> transactionNames = new HashSet<>();
        futures.add(rollupTransactionSummary(rollup, query, transactionNames));
        futures.add(rollupTransactionErrorSummary(rollup, query));

        ScratchBuffer scratchBuffer = new ScratchBuffer();
        futures.addAll(rollupOtherParts(rollup, query, scratchBuffer));

        for (String transactionName : transactionNames) {
            futures.addAll(rollupOtherParts(rollup, query.withTransactionName(transactionName),
                    scratchBuffer));
        }
        return futures;
    }

    private List<CompletionStage<?>> rollupOtherParts(RollupParams rollup, AggregateQuery query,
                                                      ScratchBuffer scratchBuffer) {
        List<CompletionStage<?>> futures = new ArrayList<>();
        futures.add(rollupOverview(rollup, query));
        futures.add(rollupHistogram(rollup, query, scratchBuffer));
        futures.add(rollupThroughput(rollup, query));
        futures.add(rollupQueries(rollup, query));
        futures.add(rollupServiceCalls(rollup, query));
        futures.add(rollupThreadProfile(rollup, query, mainThreadProfileTable));
        futures.add(rollupThreadProfile(rollup, query, auxThreadProfileTable));
        return futures;
    }

    private List<CompletionStage<?>> rollupOtherPartsFromChildren(RollupParams rollup,
                                                                  AggregateQuery query, Collection<String> childAgentRollupIds,
                                                                  ScratchBuffer scratchBuffer) {
        List<CompletionStage<?>> futures = new ArrayList<>();
        futures.add(rollupOverviewFromChildren(rollup, query, childAgentRollupIds));
        futures.add(rollupHistogramFromChildren(rollup, query, childAgentRollupIds, scratchBuffer));
        futures.add(rollupThroughputFromChildren(rollup, query, childAgentRollupIds));
        futures.add(rollupQueriesFromChildren(rollup, query, childAgentRollupIds));
        futures.add(rollupServiceCallsFromChildren(rollup, query, childAgentRollupIds));
        futures.add(rollupThreadProfileFromChildren(rollup, query, childAgentRollupIds,
                mainThreadProfileTable));
        futures.add(rollupThreadProfileFromChildren(rollup, query, childAgentRollupIds,
                auxThreadProfileTable));
        return futures;
    }

    private CompletableFuture<?> rollupOverallSummary(RollupParams rollup, AggregateQuery query) {
        CompletableFuture<AsyncResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, summaryTable, true);
        return MoreFutures.rollupAsync(future, asyncExecutor, new MoreFutures.DoRollup() {
            @Override
            public CompletableFuture<?> execute(AsyncResultSet rows) {
                return rollupOverallSummaryFromRows(rollup, query, Lists.newArrayList(rows));
            }
        });
    }

    private CompletionStage<?> rollupOverallSummaryFromChildren(RollupParams rollup,
                                                                AggregateQuery query, Collection<String> childAgentRollupIds) {
        List<CompletionStage<AsyncResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, summaryTable, true);
        return CompletableFutures.allAsList(futures).thenCompose(rows -> {
            return rollupOverallSummaryFromRows(rollup, query, rows);
        });
    }

    private CompletableFuture<?> rollupOverallSummaryFromRows(RollupParams rollup,
                                                              AggregateQuery query, List<AsyncResultSet> results) {
        DoubleAccumulator totalDurationNanos = new DoubleAccumulator(Double::sum, 0.0);
        DoubleAccumulator totalCpuNanos = new DoubleAccumulator(Double::sum, 0.0);
        DoubleAccumulator totalAllocatedBytes = new DoubleAccumulator(Double::sum, 0.0);
        AtomicLong transactionCount = new AtomicLong(0);

        Function<AsyncResultSet, CompletableFuture<?>> compute =
                new Function<AsyncResultSet, CompletableFuture<?>>() {
                    @Override
                    public CompletableFuture<?> apply(AsyncResultSet result) {
                        for (Row row : result.currentPage()) {
                            totalDurationNanos.accumulate(row.getDouble(0));
                            totalCpuNanos.accumulate(row.getDouble(1));
                            totalAllocatedBytes.accumulate(row.getDouble(2));
                            transactionCount.addAndGet(row.getLong(3));
                        }
                        if (result.hasMorePages()) {
                            return result.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                        }
                        return CompletableFuture.completedFuture(null);
                    }
                };

        return CompletableFutures.allAsList(results.stream()
                        .map(compute::apply).collect(Collectors.toList()))
                .thenCompose(ignored -> {
                    int i = 0;
                    BoundStatement boundStatement =
                            getInsertOverallPS(summaryTable, rollup.rollupLevel()).bind()
                                    .setString(i++, rollup.agentRollupId())
                                    .setString(i++, query.transactionType())
                                    .setInstant(i++, Instant.ofEpochMilli(query.to()))
                                    .setDouble(i++, totalDurationNanos.doubleValue())
                                    .setDouble(i++, totalCpuNanos.doubleValue())
                                    .setDouble(i++, totalAllocatedBytes.doubleValue())
                                    .setLong(i++, transactionCount.get())
                                    .setInt(i++, rollup.adjustedTTL().generalTTL());
                    return session.writeAsync(boundStatement, CassandraProfile.rollup).toCompletableFuture();
                });
    }

    private CompletableFuture<?> rollupErrorSummary(RollupParams rollup, AggregateQuery query) {
        CompletableFuture<AsyncResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, errorSummaryTable, false);
        return MoreFutures.rollupAsync(future, asyncExecutor, new MoreFutures.DoRollup() {
            @Override
            public CompletableFuture<?> execute(AsyncResultSet rows) {
                return rollupErrorSummaryFromRows(rollup, query, Lists.newArrayList(rows));
            }
        });
    }

    private CompletionStage<?> rollupErrorSummaryFromChildren(RollupParams rollup,
                                                              AggregateQuery query, Collection<String> childAgentRollupIds) {
        List<CompletionStage<AsyncResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, errorSummaryTable, false);
        return CompletableFutures.allAsList(futures).thenCompose(rows -> {
            return rollupErrorSummaryFromRows(rollup, query, rows);
        });
    }

    private CompletableFuture<?> rollupErrorSummaryFromRows(RollupParams rollup,
                                                            AggregateQuery query, List<AsyncResultSet> results) {
        AtomicLong errorCount = new AtomicLong(0);
        AtomicLong transactionCount = new AtomicLong(0);
        Function<AsyncResultSet, CompletableFuture<?>> compute = new Function<AsyncResultSet, CompletableFuture<?>>() {
            @Override
            public CompletableFuture<?> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    errorCount.addAndGet(row.getLong(0));
                    transactionCount.addAndGet(row.getLong(1));
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return CompletableFutures.allAsList(results.stream()
                        .map(compute::apply).collect(Collectors.toList()))
                .thenCompose(ignored -> {
                    int i = 0;
                    BoundStatement boundStatement =
                            getInsertOverallPS(errorSummaryTable, rollup.rollupLevel()).bind()
                                    .setString(i++, rollup.agentRollupId())
                                    .setString(i++, query.transactionType())
                                    .setInstant(i++, Instant.ofEpochMilli(query.to()))
                                    .setLong(i++, errorCount.get())
                                    .setLong(i++, transactionCount.get())
                                    .setInt(i++, rollup.adjustedTTL().generalTTL());
                    return session.writeAsync(boundStatement, CassandraProfile.rollup).toCompletableFuture();
                });
    }

    // transactionNames is passed in empty, and populated synchronously by this method
    private CompletableFuture<?> rollupTransactionSummary(RollupParams rollup,
                                                          AggregateQuery query, Set<String> transactionNames) {
        BoundStatement boundStatement = checkNotNull(readTransactionForRollupPS.get(summaryTable))
                .get(query.rollupLevel()).bind();
        boundStatement = bindQuery(boundStatement, rollup.agentRollupId(), query);

        Map<String, MutableSummary> summaries = new HashMap<>();
        Function<AsyncResultSet, CompletableFuture<Map<String, MutableSummary>>> compute = new Function<AsyncResultSet, CompletableFuture<Map<String, MutableSummary>>>() {
            @Override
            public CompletableFuture<Map<String, MutableSummary>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    String transactionName = mergeRowIntoSummaries(row, summaries);
                    transactionNames.add(transactionName);
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(summaries);
            }
        };
        return session.readAsync(boundStatement, CassandraProfile.rollup)
                .thenCompose(compute)
                .thenCompose(map -> insertTransactionSummaries(rollup, query, map, CassandraProfile.rollup)).toCompletableFuture();
    }

    // transactionNames is passed in empty, and populated synchronously by this method
    private CompletableFuture<?> rollupTransactionSummaryFromChildren(RollupParams rollup,
                                                                      AggregateQuery query, Collection<String> childAgentRollupIds,
                                                                      Multimap<String, String> transactionNames) {
        Map<String, CompletionStage<AsyncResultSet>> futures =
                getRowsForSummaryRollupFromChildren(query, childAgentRollupIds, summaryTable, true);
        Map<String, MutableSummary> summaries = new HashMap<>();

        List<CompletionStage<?>> waitList = new ArrayList<>();
        for (Map.Entry<String, CompletionStage<AsyncResultSet>> entry : futures.entrySet()) {
            String childAgentRollupId = entry.getKey();

            Function<AsyncResultSet, CompletableFuture<?>> compute = new Function<AsyncResultSet, CompletableFuture<?>>() {
                @Override
                public CompletableFuture<?> apply(AsyncResultSet asyncResultSet) {
                    for (Row row : asyncResultSet.currentPage()) {
                        String transactionName;
                        synchronized (summaries) {
                            transactionName = mergeRowIntoSummaries(row, summaries);
                        }
                        synchronized (transactionNames) {
                            transactionNames.put(transactionName, childAgentRollupId);
                        }
                    }
                    if (asyncResultSet.hasMorePages()) {
                        return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                    }
                    return CompletableFuture.completedFuture(null);
                }
            };
            waitList.add(entry.getValue().thenCompose(compute::apply));
        }
        return CompletableFutures.allAsList(waitList)
                .thenCompose(ignored -> insertTransactionSummaries(rollup, query, summaries, CassandraProfile.rollup));
    }

    private CompletableFuture<?> insertTransactionSummaries(RollupParams rollup,
                                                            AggregateQuery query, Map<String, MutableSummary> summaries, CassandraProfile profile) {
        BoundStatement boundStatement;
        List<CompletableFuture<?>> futures = new ArrayList<>();
        PreparedStatement preparedStatement =
                getInsertTransactionPS(summaryTable, rollup.rollupLevel());
        for (Map.Entry<String, MutableSummary> entry : summaries.entrySet()) {
            MutableSummary summary = entry.getValue();
            int i = 0;
            boundStatement = preparedStatement.bind()
                    .setString(i++, rollup.agentRollupId())
                    .setString(i++, query.transactionType())
                    .setInstant(i++, Instant.ofEpochMilli(query.to()))
                    .setString(i++, entry.getKey())
                    .setDouble(i++, summary.totalDurationNanos)
                    .setDouble(i++, summary.totalCpuNanos)
                    .setDouble(i++, summary.totalAllocatedBytes)
                    .setLong(i++, summary.transactionCount)
                    .setInt(i++, rollup.adjustedTTL().generalTTL());
            futures.add(session.writeAsync(boundStatement, profile).toCompletableFuture());
        }
        return CompletableFutures.allAsList(futures);
    }

    private CompletableFuture<?> rollupTransactionErrorSummary(RollupParams rollup,
                                                               AggregateQuery query) {
        BoundStatement boundStatement =
                checkNotNull(readTransactionForRollupPS.get(errorSummaryTable))
                        .get(query.rollupLevel()).bind();
        boundStatement = bindQuery(boundStatement, rollup.agentRollupId(), query);
        CompletableFuture<AsyncResultSet> future = session.readAsync(boundStatement, CassandraProfile.rollup).toCompletableFuture();
        return MoreFutures.rollupAsync(future, asyncExecutor, new MoreFutures.DoRollup() {
            @Override
            public CompletableFuture<?> execute(AsyncResultSet rows) {
                return rollupTransactionErrorSummaryFromRows(rollup, query, Lists.newArrayList(rows));
            }
        });
    }

    private CompletableFuture<?> rollupTransactionErrorSummaryFromChildren(RollupParams rollup,
                                                                           AggregateQuery query, Collection<String> childAgentRollupIds) {
        Map<String, CompletionStage<AsyncResultSet>> futures = getRowsForSummaryRollupFromChildren(
                query, childAgentRollupIds, errorSummaryTable, false);

        return CompletableFutures.allAsList(new ArrayList<>(futures.values())).thenCompose(rows -> {
            return rollupTransactionErrorSummaryFromRows(rollup, query, rows);
        });
    }

    private CompletableFuture<?> rollupTransactionErrorSummaryFromRows(RollupParams rollup,
                                                                       AggregateQuery query, List<AsyncResultSet> results) {
        Map<String, MutableErrorSummary> summaries = new ConcurrentHashMap<>();
        Function<AsyncResultSet, CompletableFuture<?>> compute = new Function<AsyncResultSet, CompletableFuture<?>>() {
            @Override
            public CompletableFuture<?> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    int i = 0;
                    String transactionName = checkNotNull(row.getString(i++));
                    synchronized (summaries) {
                        MutableErrorSummary summary = summaries.get(transactionName);
                        if (summary == null) {
                            summary = new MutableErrorSummary();
                            summaries.put(transactionName, summary);
                        }
                        summary.errorCount += row.getLong(i++);
                        summary.transactionCount += row.getLong(i++);
                    }
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return CompletableFutures.allAsList(results.stream()
                        .map(compute::apply).collect(Collectors.toList()))
                .thenCompose(ignored -> {

                    PreparedStatement preparedStatement =
                            getInsertTransactionPS(errorSummaryTable, rollup.rollupLevel());
                    List<CompletableFuture<?>> futures = new ArrayList<>();
                    for (Map.Entry<String, MutableErrorSummary> entry : summaries.entrySet()) {
                        MutableErrorSummary summary = entry.getValue();
                        int i = 0;
                        BoundStatement boundStatement = preparedStatement.bind()
                                .setString(i++, rollup.agentRollupId())
                                .setString(i++, query.transactionType())
                                .setInstant(i++, Instant.ofEpochMilli(query.to()))
                                .setString(i++, entry.getKey())
                                .setLong(i++, summary.errorCount)
                                .setLong(i++, summary.transactionCount)
                                .setInt(i++, rollup.adjustedTTL().generalTTL());
                        futures.add(session.writeAsync(boundStatement, CassandraProfile.rollup).toCompletableFuture());
                    }
                    return CompletableFutures.allAsList(futures);
                });
    }

    private CompletableFuture<?> rollupOverview(RollupParams rollup, AggregateQuery query) {
        CompletableFuture<AsyncResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, overviewTable, true);
        return MoreFutures.rollupAsync(future, asyncExecutor, new MoreFutures.DoRollup() {
            @Override
            public CompletableFuture<?> execute(AsyncResultSet rows) {
                return rollupOverviewFromRows(rollup, query, Lists.newArrayList(rows));
            }
        });
    }

    private CompletableFuture<?> rollupOverviewFromChildren(RollupParams rollup,
                                                            AggregateQuery query, Collection<String> childAgentRollupIds) {
        List<CompletionStage<AsyncResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, overviewTable, true);
        return CompletableFutures.allAsList(futures).thenCompose(rows -> {
            return rollupOverviewFromRows(rollup, query, rows);
        });
    }

    private CompletableFuture<?> rollupOverviewFromRows(RollupParams rollup, AggregateQuery query,
                                                        List<AsyncResultSet> results) {
        DoubleAccumulator totalDurationNanos = new DoubleAccumulator(Double::sum, 0.0);
        AtomicLong transactionCount = new AtomicLong(0);
        AtomicBoolean asyncTransactions = new AtomicBoolean(false);
        List<MutableTimer> mainThreadRootTimers = new ArrayList<>();
        MutableThreadStats mainThreadStats = new MutableThreadStats();
        MutableTimer auxThreadRootTimer = MutableTimer.createAuxThreadRootTimer();
        MutableThreadStats auxThreadStats = new MutableThreadStats();
        List<MutableTimer> asyncTimers = new ArrayList<>();

        Function<AsyncResultSet, CompletableFuture<?>> compute = new Function<AsyncResultSet, CompletableFuture<?>>() {
            @Override
            public CompletableFuture<?> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    int i = 0;
                    totalDurationNanos.accumulate(row.getDouble(i++));
                    transactionCount.addAndGet(row.getLong(i++));
                    if (row.getBoolean(i++)) {
                        asyncTransactions.set(true);
                    }
                    List<Aggregate.Timer> toBeMergedMainThreadRootTimers =
                            Messages.parseDelimitedFrom(row.getByteBuffer(i++), Aggregate.Timer.parser());
                    synchronized (mainThreadRootTimers) {
                        MutableAggregate.mergeRootTimers(toBeMergedMainThreadRootTimers, mainThreadRootTimers);
                    }
                    synchronized (mainThreadStats) {
                        mainThreadStats.addTotalCpuNanos(getNextThreadStat(row, i++));
                        mainThreadStats.addTotalBlockedNanos(getNextThreadStat(row, i++));
                        mainThreadStats.addTotalWaitedNanos(getNextThreadStat(row, i++));
                        mainThreadStats.addTotalAllocatedBytes(getNextThreadStat(row, i++));
                    }
                    // reading delimited singleton list for backwards compatibility with data written
                    // prior to 0.12.0
                    List<Aggregate.Timer> list =
                            Messages.parseDelimitedFrom(row.getByteBuffer(i++), Aggregate.Timer.parser());
                    Aggregate.Timer toBeMergedAuxThreadRootTimer = list.isEmpty() ? null : list.get(0);
                    if (toBeMergedAuxThreadRootTimer == null) {
                        i += 4;
                    } else {
                        synchronized (auxThreadRootTimer) {
                            auxThreadRootTimer.merge(toBeMergedAuxThreadRootTimer);
                        }
                        synchronized (auxThreadStats) {
                            auxThreadStats.addTotalCpuNanos(getNextThreadStat(row, i++));
                            auxThreadStats.addTotalBlockedNanos(getNextThreadStat(row, i++));
                            auxThreadStats.addTotalWaitedNanos(getNextThreadStat(row, i++));
                            auxThreadStats.addTotalAllocatedBytes(getNextThreadStat(row, i++));
                        }
                    }
                    List<Aggregate.Timer> toBeMergedAsyncTimers =
                            Messages.parseDelimitedFrom(row.getByteBuffer(i++), Aggregate.Timer.parser());
                    synchronized (asyncTimers) {
                        MutableAggregate.mergeRootTimers(toBeMergedAsyncTimers, asyncTimers);
                    }
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return CompletableFutures.allAsList(results.stream()
                        .map(compute::apply).collect(Collectors.toList()))
                .thenCompose(ignored -> {

                    BoundStatement boundStatement;
                    if (query.transactionName() == null) {
                        boundStatement = getInsertOverallPS(overviewTable, rollup.rollupLevel()).bind();
                    } else {
                        boundStatement = getInsertTransactionPS(overviewTable, rollup.rollupLevel()).bind();
                    }
                    int i = 0;
                    boundStatement = boundStatement.setString(i++, rollup.agentRollupId())
                            .setString(i++, query.transactionType());
                    if (query.transactionName() != null) {
                        boundStatement = boundStatement.setString(i++, query.transactionName());
                    }
                    boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(query.to()))
                            .setDouble(i++, totalDurationNanos.doubleValue())
                            .setLong(i++, transactionCount.get())
                            .setBoolean(i++, asyncTransactions.get())
                            .setByteBuffer(i++,
                                    Messages.toByteBuffer(MutableAggregate.toProto(mainThreadRootTimers)))
                            .setDouble(i++, mainThreadStats.getTotalCpuNanos())
                            .setDouble(i++, mainThreadStats.getTotalBlockedNanos())
                            .setDouble(i++, mainThreadStats.getTotalWaitedNanos())
                            .setDouble(i++, mainThreadStats.getTotalAllocatedBytes());
                    if (auxThreadRootTimer.getCount() == 0) {
                        boundStatement = boundStatement.setToNull(i++)
                                .setToNull(i++)
                                .setToNull(i++)
                                .setToNull(i++)
                                .setToNull(i++);
                    } else {
                        // writing as delimited singleton list for backwards compatibility with data written
                        // prior to 0.12.0
                        boundStatement = boundStatement.setByteBuffer(i++, Messages
                                        .toByteBuffer(MutableAggregate.toProto(ImmutableList.of(auxThreadRootTimer))))
                                .setDouble(i++, auxThreadStats.getTotalCpuNanos())
                                .setDouble(i++, auxThreadStats.getTotalBlockedNanos())
                                .setDouble(i++, auxThreadStats.getTotalWaitedNanos())
                                .setDouble(i++, auxThreadStats.getTotalAllocatedBytes());
                    }
                    if (asyncTimers.isEmpty()) {
                        boundStatement = boundStatement.setToNull(i++);
                    } else {
                        boundStatement = boundStatement.setByteBuffer(i++,
                                Messages.toByteBuffer(MutableAggregate.toProto(asyncTimers)));
                    }
                    boundStatement = boundStatement.setInt(i++, rollup.adjustedTTL().generalTTL());
                    return session.writeAsync(boundStatement, CassandraProfile.rollup).toCompletableFuture();
                });
    }

    private CompletableFuture<?> rollupHistogram(RollupParams rollup, AggregateQuery query,
                                                 ScratchBuffer scratchBuffer) {
        CompletableFuture<AsyncResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, histogramTable, true);
        return MoreFutures.rollupAsync(future, asyncExecutor, new MoreFutures.DoRollup() {
            @Override
            public CompletableFuture<?> execute(AsyncResultSet rows) {
                return rollupHistogramFromRows(rollup, query, Lists.newArrayList(rows), scratchBuffer);
            }
        });
    }

    private CompletionStage<?> rollupHistogramFromChildren(RollupParams rollup,
                                                           AggregateQuery query, Collection<String> childAgentRollupIds,
                                                           ScratchBuffer scratchBuffer) {
        List<CompletionStage<AsyncResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, histogramTable, true);
        return CompletableFutures.allAsList(futures).thenCompose(rows -> {
            return rollupHistogramFromRows(rollup, query, rows, scratchBuffer);
        });
    }

    private CompletableFuture<?> rollupHistogramFromRows(RollupParams rollup, AggregateQuery query,
                                                         List<AsyncResultSet> results, ScratchBuffer scratchBuffer) {
        DoubleAccumulator totalDurationNanos = new DoubleAccumulator(Double::sum, 0.0);
        AtomicLong transactionCount = new AtomicLong(0);
        LazyHistogram durationNanosHistogram = new LazyHistogram();
        Function<AsyncResultSet, CompletableFuture<?>> compute = new Function<AsyncResultSet, CompletableFuture<?>>() {
            @Override
            public CompletableFuture<?> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    int i = 0;
                    totalDurationNanos.accumulate(row.getDouble(i++));
                    transactionCount.addAndGet(row.getLong(i++));
                    ByteBuffer bytes = checkNotNull(row.getByteBuffer(i++));
                    synchronized (durationNanosHistogram) {
                        try {
                            durationNanosHistogram.merge(Aggregate.Histogram.parseFrom(bytes));
                        } catch (InvalidProtocolBufferException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return CompletableFutures.allAsList(results.stream()
                        .map(compute::apply).collect(Collectors.toList()))
                .thenCompose(ignored -> {

                    BoundStatement boundStatement;
                    if (query.transactionName() == null) {
                        boundStatement = getInsertOverallPS(histogramTable, rollup.rollupLevel()).bind();
                    } else {
                        boundStatement = getInsertTransactionPS(histogramTable, rollup.rollupLevel()).bind();
                    }
                    int i = 0;
                    boundStatement = boundStatement.setString(i++, rollup.agentRollupId())
                            .setString(i++, query.transactionType());
                    if (query.transactionName() != null) {
                        boundStatement = boundStatement.setString(i++, query.transactionName());
                    }
                    boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(query.to()))
                            .setDouble(i++, totalDurationNanos.doubleValue())
                            .setLong(i++, transactionCount.get())
                            .setByteBuffer(i++, toByteBuffer(durationNanosHistogram.toProto(scratchBuffer)))
                            .setInt(i++, rollup.adjustedTTL().generalTTL());
                    return session.writeAsync(boundStatement, CassandraProfile.rollup).toCompletableFuture();
                });
    }

    private CompletableFuture<?> rollupThroughput(RollupParams rollup, AggregateQuery query) {
        CompletableFuture<AsyncResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, throughputTable, true);
        return MoreFutures.rollupAsync(future, asyncExecutor, new MoreFutures.DoRollup() {
            @Override
            public CompletableFuture<?> execute(AsyncResultSet rows) {
                return rollupThroughputFromRows(rollup, query, Lists.newArrayList(rows));
            }
        });
    }

    private CompletionStage<?> rollupThroughputFromChildren(RollupParams rollup,
                                                            AggregateQuery query, Collection<String> childAgentRollupIds) {
        List<CompletionStage<AsyncResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, throughputTable, true);
        return CompletableFutures.allAsList(futures).thenCompose(rows -> {
            return rollupThroughputFromRows(rollup, query, rows);
        });
    }

    private CompletableFuture<?> rollupThroughputFromRows(RollupParams rollup, AggregateQuery query,
                                                          List<AsyncResultSet> results) {
        AtomicLong transactionCount = new AtomicLong(0);
        // error_count is null for data inserted prior to glowroot central 0.9.18
        // rolling up any interval with null error_count should result in null error_count
        AtomicBoolean hasMissingErrorCount = new AtomicBoolean(false);
        AtomicLong errorCount = new AtomicLong(0);

        Function<AsyncResultSet, CompletableFuture<?>> compute = new Function<AsyncResultSet, CompletableFuture<?>>() {
            @Override
            public CompletableFuture<?> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    transactionCount.addAndGet(row.getLong(0));
                    if (row.isNull(1)) {
                        hasMissingErrorCount.set(true);
                    } else {
                        errorCount.addAndGet(row.getLong(1));
                    }
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };

        return CompletableFutures.allAsList(results.stream()
                        .map(compute::apply).collect(Collectors.toList()))
                .thenCompose(ignored -> {

                    BoundStatement boundStatement;
                    if (query.transactionName() == null) {
                        boundStatement = getInsertOverallPS(throughputTable, rollup.rollupLevel()).bind();
                    } else {
                        boundStatement = getInsertTransactionPS(throughputTable, rollup.rollupLevel()).bind();
                    }
                    int i = 0;
                    boundStatement = boundStatement.setString(i++, rollup.agentRollupId())
                            .setString(i++, query.transactionType());
                    if (query.transactionName() != null) {
                        boundStatement = boundStatement.setString(i++, query.transactionName());
                    }
                    boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(query.to()))
                            .setLong(i++, transactionCount.get());
                    if (hasMissingErrorCount.get()) {
                        boundStatement = boundStatement.setToNull(i++);
                    } else {
                        boundStatement = boundStatement.setLong(i++, errorCount.get());
                    }
                    boundStatement = boundStatement.setInt(i++, rollup.adjustedTTL().generalTTL());
                    return session.writeAsync(boundStatement, CassandraProfile.rollup).toCompletableFuture();
                });
    }

    @CheckReturnValue
    private CompletionStage<?> rollupQueries(RollupParams rollup, AggregateQuery query) {
        CompletableFuture<AsyncResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, queryTable, false);
        return future.thenCompose(rows -> {
            return rollupQueriesFromRows(rollup, query, Lists.newArrayList(rows));
        });
    }

    private CompletionStage<?> rollupQueriesFromChildren(RollupParams rollup,
                                                         AggregateQuery query, Collection<String> childAgentRollupIds) {
        List<CompletionStage<AsyncResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, queryTable, false);
        return CompletableFutures.allAsList(futures).thenCompose(rows -> {
            return rollupQueriesFromRows(rollup, query, rows);
        });
    }

    private CompletableFuture<?> rollupQueriesFromRows(RollupParams rollup, AggregateQuery query,
                                                       List<AsyncResultSet> results) {
        QueryCollector collector =
                new QueryCollector(rollup.maxQueryAggregatesPerTransactionAggregate());

        Function<AsyncResultSet, CompletableFuture<?>> compute = new Function<AsyncResultSet, CompletableFuture<?>>() {
            @Override
            public CompletableFuture<?> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    int i = 0;
                    String queryType = checkNotNull(row.getString(i++));
                    String truncatedText = checkNotNull(row.getString(i++));
                    // full_query_text_sha1 cannot be null since it is used in clustering key
                    String fullTextSha1 = Strings.emptyToNull(row.getString(i++));
                    double totalDurationNanos = row.getDouble(i++);
                    long executionCount = row.getLong(i++);
                    boolean hasTotalRows = !row.isNull(i);
                    long totalRows = row.getLong(i++);
                    synchronized (collector) {
                        collector.mergeQuery(queryType, truncatedText, fullTextSha1, totalDurationNanos,
                                executionCount, hasTotalRows, totalRows);
                    }
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return CompletableFutures.allAsList(results.stream()
                        .map(compute::apply).collect(Collectors.toList()))
                .thenCompose(ignored -> insertQueries(collector.getSortedAndTruncatedQueries(), rollup.rollupLevel(),
                        rollup.agentRollupId(), query.transactionType(), query.transactionName(),
                        query.to(), rollup.adjustedTTL()));
    }

    private CompletionStage<?> rollupServiceCalls(RollupParams rollup, AggregateQuery query) {
        return executeQueryForRollup(rollup.agentRollupId(), query, serviceCallTable, false).thenCompose(rows -> {
            return rollupServiceCallsFromRows(rollup, query, Lists.newArrayList(rows));
        });
    }

    private CompletionStage<?> rollupServiceCallsFromChildren(RollupParams rollup,
                                                              AggregateQuery query, Collection<String> childAgentRollupIds) {
        List<CompletionStage<AsyncResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, serviceCallTable, false);
        return CompletableFutures.allAsList(futures).thenCompose(rows -> {
            return rollupServiceCallsFromRows(rollup, query, rows);
        });
    }

    private CompletableFuture<?> rollupServiceCallsFromRows(RollupParams rollup,
                                                            AggregateQuery query, List<AsyncResultSet> results) {
        ServiceCallCollector collector =
                new ServiceCallCollector(rollup.maxServiceCallAggregatesPerTransactionAggregate());

        Function<AsyncResultSet, CompletableFuture<?>> compute = new Function<AsyncResultSet, CompletableFuture<?>>() {
            @Override
            public CompletableFuture<?> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    int i = 0;
                    String serviceCallType = checkNotNull(row.getString(i++));
                    String serviceCallText = checkNotNull(row.getString(i++));
                    double totalDurationNanos = row.getDouble(i++);
                    long executionCount = row.getLong(i++);
                    synchronized (collector) {
                        collector.mergeServiceCall(serviceCallType, serviceCallText, totalDurationNanos,
                                executionCount);
                    }
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return CompletableFutures.allAsList(results.stream()
                        .map(compute::apply).collect(Collectors.toList()))
                .thenCompose(ignored -> insertServiceCalls(collector.getSortedAndTruncatedServiceCalls(),
                        rollup.rollupLevel(), rollup.agentRollupId(), query.transactionType(),
                        query.transactionName(), query.to(), rollup.adjustedTTL()));
    }

    private CompletableFuture<?> rollupThreadProfile(RollupParams rollup, AggregateQuery query,
                                                     Table table) {
        CompletableFuture<AsyncResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, table, false);
        return MoreFutures.rollupAsync(future, asyncExecutor, new MoreFutures.DoRollup() {
            @Override
            public CompletableFuture<?> execute(AsyncResultSet rows) {
                return rollupThreadProfileFromRows(rollup, query, Lists.newArrayList(rows), table);
            }
        });
    }

    private CompletionStage<?> rollupThreadProfileFromChildren(RollupParams rollup,
                                                               AggregateQuery query, Collection<String> childAgentRollupIds, Table table) {
        List<CompletionStage<AsyncResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, table, false);
        return CompletableFutures.allAsList(futures).thenCompose(rows -> {
            return rollupThreadProfileFromRows(rollup, query, rows, table);
        });
    }

    private CompletableFuture<?> rollupThreadProfileFromRows(RollupParams rollup,
                                                             AggregateQuery query, List<AsyncResultSet> results, Table table) {
        MutableProfile profile = new MutableProfile();
        Function<AsyncResultSet, CompletableFuture<?>> compute = new Function<AsyncResultSet, CompletableFuture<?>>() {
            @Override
            public CompletableFuture<?> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    ByteBuffer bytes = checkNotNull(row.getByteBuffer(0));
                    synchronized (profile) {
                        try {
                            profile.merge(Profile.parseFrom(bytes));
                        } catch (InvalidProtocolBufferException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return CompletableFutures.allAsList(results.stream()
                        .map(compute::apply).collect(Collectors.toList()))
                .thenCompose(ignored -> {

                    BoundStatement boundStatement;
                    if (query.transactionName() == null) {
                        boundStatement = getInsertOverallPS(table, rollup.rollupLevel()).bind();
                    } else {
                        boundStatement = getInsertTransactionPS(table, rollup.rollupLevel()).bind();
                    }
                    int i = 0;
                    boundStatement = boundStatement.setString(i++, rollup.agentRollupId())
                            .setString(i++, query.transactionType());
                    if (query.transactionName() != null) {
                        boundStatement = boundStatement.setString(i++, query.transactionName());
                    }
                    boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(query.to()))
                            .setByteBuffer(i++, toByteBuffer(profile.toProto()))
                            .setInt(i++, rollup.adjustedTTL().profileTTL());
                    return session.writeAsync(boundStatement, CassandraProfile.rollup).toCompletableFuture();
                });
    }

    private Map<String, CompletionStage<AsyncResultSet>> getRowsForSummaryRollupFromChildren(
            AggregateQuery query, Collection<String> childAgentRollupIds, Table table,
            boolean warnIfNoResults) {
        Map<String, CompletionStage<AsyncResultSet>> futures = new HashMap<>();
        for (String childAgentRollupId : childAgentRollupIds) {
            BoundStatement boundStatement =
                    checkNotNull(readTransactionForRollupFromChildPS.get(table)).bind();
            boundStatement = bindQueryForRollupFromChild(boundStatement, childAgentRollupId, query);
            if (warnIfNoResults) {
                futures.put(childAgentRollupId, session.readAsyncWarnIfNoRows(boundStatement, rollup,
                        "no summary table records found for agentRollupId={}, query={}",
                        childAgentRollupId, query).toCompletableFuture());
            } else {
                futures.put(childAgentRollupId, session.readAsync(boundStatement, rollup).toCompletableFuture());
            }
        }
        return futures;
    }

    private List<CompletionStage<AsyncResultSet>> getRowsForRollupFromChildren(AggregateQuery query,
                                                                               Collection<String> childAgentRollupIds, Table table, boolean warnIfNoResults) {
        List<CompletionStage<AsyncResultSet>> futures = new ArrayList<>();
        for (String childAgentRollupId : childAgentRollupIds) {
            futures.add(executeQueryForRollupFromChild(childAgentRollupId, query, table,
                    warnIfNoResults));
        }
        return futures;
    }

    @CheckReturnValue
    private List<CompletableFuture<?>> storeOverallAggregate(String agentRollupId,
                                                             String transactionType, long captureTime, Aggregate aggregate,
                                                             List<Aggregate.SharedQueryText> sharedQueryTexts, TTL adjustedTTL) {

        final int rollupLevel = 0;

        List<CompletableFuture<?>> futures = new ArrayList<>();
        int i = 0;
        BoundStatement boundStatement = getInsertOverallPS(summaryTable, rollupLevel).bind()
                .setString(i++, agentRollupId)
                .setString(i++, transactionType)
                .setInstant(i++, Instant.ofEpochMilli(captureTime));
        boundStatement = bindAggregateForSummary(boundStatement, aggregate, i, adjustedTTL);
        futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());

        if (aggregate.getErrorCount() > 0) {
            i = 0;
            boundStatement = getInsertOverallPS(errorSummaryTable, rollupLevel).bind()
                    .setString(i++, agentRollupId)
                    .setString(i++, transactionType)
                    .setInstant(i++, Instant.ofEpochMilli(captureTime))
                    .setLong(i++, aggregate.getErrorCount())
                    .setLong(i++, aggregate.getTransactionCount())
                    .setInt(i++, adjustedTTL.generalTTL());
            futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
        }

        i = 0;
        boundStatement = getInsertOverallPS(overviewTable, rollupLevel).bind()
                .setString(i++, agentRollupId)
                .setString(i++, transactionType)
                .setInstant(i++, Instant.ofEpochMilli(captureTime));
        boundStatement = bindAggregate(boundStatement, aggregate, i++, adjustedTTL);
        futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());

        i = 0;
        boundStatement = getInsertOverallPS(histogramTable, rollupLevel).bind()
                .setString(i++, agentRollupId)
                .setString(i++, transactionType)
                .setInstant(i++, Instant.ofEpochMilli(captureTime))
                .setDouble(i++, aggregate.getTotalDurationNanos())
                .setLong(i++, aggregate.getTransactionCount())
                .setByteBuffer(i++, toByteBuffer(aggregate.getDurationNanosHistogram()))
                .setInt(i++, adjustedTTL.generalTTL());
        futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());

        i = 0;
        boundStatement = getInsertOverallPS(throughputTable, rollupLevel).bind()
                .setString(i++, agentRollupId)
                .setString(i++, transactionType)
                .setInstant(i++, Instant.ofEpochMilli(captureTime))
                .setLong(i++, aggregate.getTransactionCount())
                .setLong(i++, aggregate.getErrorCount())
                .setInt(i++, adjustedTTL.generalTTL());
        futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            i = 0;
            boundStatement = getInsertOverallPS(mainThreadProfileTable, rollupLevel).bind()
                    .setString(i++, agentRollupId)
                    .setString(i++, transactionType)
                    .setInstant(i++, Instant.ofEpochMilli(captureTime))
                    .setByteBuffer(i++, toByteBuffer(profile))
                    .setInt(i++, adjustedTTL.profileTTL());
            futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            i = 0;
            boundStatement = getInsertOverallPS(auxThreadProfileTable, rollupLevel).bind()
                    .setString(i++, agentRollupId)
                    .setString(i++, transactionType)
                    .setInstant(i++, Instant.ofEpochMilli(captureTime))
                    .setByteBuffer(i++, toByteBuffer(profile))
                    .setInt(i++, adjustedTTL.profileTTL());
            futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
        }
        futures.addAll(insertQueries(getQueries(aggregate), sharedQueryTexts, rollupLevel,
                agentRollupId, transactionType, null, captureTime, adjustedTTL));
        futures.addAll(insertServiceCallsProto(getServiceCalls(aggregate), rollupLevel,
                agentRollupId, transactionType, null, captureTime, adjustedTTL));
        return futures;
    }

    private List<CompletableFuture<?>> storeTransactionAggregate(String agentRollupId, String transactionType,
                                                                 String transactionName, long captureTime, Aggregate aggregate,
                                                                 List<Aggregate.SharedQueryText> sharedQueryTexts, TTL adjustedTTL) {

        final int rollupLevel = 0;

        List<CompletableFuture<?>> futures = new ArrayList<>();
        int i = 0;
        BoundStatement boundStatement = getInsertTransactionPS(overviewTable, rollupLevel).bind()
                .setString(i++, agentRollupId)
                .setString(i++, transactionType)
                .setString(i++, transactionName)
                .setInstant(i++, Instant.ofEpochMilli(captureTime));
        boundStatement = bindAggregate(boundStatement, aggregate, i++, adjustedTTL);
        futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());

        i = 0;
        boundStatement = getInsertTransactionPS(histogramTable, rollupLevel).bind()
                .setString(i++, agentRollupId)
                .setString(i++, transactionType)
                .setString(i++, transactionName)
                .setInstant(i++, Instant.ofEpochMilli(captureTime))
                .setDouble(i++, aggregate.getTotalDurationNanos())
                .setLong(i++, aggregate.getTransactionCount())
                .setByteBuffer(i++, toByteBuffer(aggregate.getDurationNanosHistogram()))
                .setInt(i++, adjustedTTL.generalTTL());
        futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());

        i = 0;
        boundStatement = getInsertTransactionPS(throughputTable, rollupLevel).bind()
                .setString(i++, agentRollupId)
                .setString(i++, transactionType)
                .setString(i++, transactionName)
                .setInstant(i++, Instant.ofEpochMilli(captureTime))
                .setLong(i++, aggregate.getTransactionCount())
                .setLong(i++, aggregate.getErrorCount())
                .setInt(i++, adjustedTTL.generalTTL());
        futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            i = 0;
            boundStatement = getInsertTransactionPS(mainThreadProfileTable, rollupLevel).bind()
                    .setString(i++, agentRollupId)
                    .setString(i++, transactionType)
                    .setString(i++, transactionName)
                    .setInstant(i++, Instant.ofEpochMilli(captureTime))
                    .setByteBuffer(i++, toByteBuffer(profile))
                    .setInt(i++, adjustedTTL.profileTTL());
            futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            i = 0;
            boundStatement = getInsertTransactionPS(auxThreadProfileTable, rollupLevel).bind()
                    .setString(i++, agentRollupId)
                    .setString(i++, transactionType)
                    .setString(i++, transactionName)
                    .setInstant(i++, Instant.ofEpochMilli(captureTime))
                    .setByteBuffer(i++, toByteBuffer(profile))
                    .setInt(i++, adjustedTTL.profileTTL());
            futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
        }
        futures.addAll(insertQueries(getQueries(aggregate), sharedQueryTexts, rollupLevel,
                agentRollupId, transactionType, transactionName, captureTime, adjustedTTL));
        futures.addAll(insertServiceCallsProto(getServiceCalls(aggregate), rollupLevel,
                agentRollupId, transactionType, transactionName, captureTime, adjustedTTL));
        return futures;
    }

    private List<CompletableFuture<?>> storeTransactionNameSummary(String agentRollupId,
                                                                   String transactionType, String transactionName, long captureTime, Aggregate aggregate,
                                                                   TTL adjustedTTL) {

        final int rollupLevel = 0;

        List<CompletableFuture<?>> futures = new ArrayList<>();
        int i = 0;
        BoundStatement boundStatement = getInsertTransactionPS(summaryTable, rollupLevel).bind()
                .setString(i++, agentRollupId)
                .setString(i++, transactionType)
                .setInstant(i++, Instant.ofEpochMilli(captureTime))
                .setString(i++, transactionName);
        boundStatement = bindAggregateForSummary(boundStatement, aggregate, i, adjustedTTL);
        futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());

        if (aggregate.getErrorCount() > 0) {
            i = 0;
            boundStatement = getInsertTransactionPS(errorSummaryTable, rollupLevel).bind()
                    .setString(i++, agentRollupId)
                    .setString(i++, transactionType)
                    .setInstant(i++, Instant.ofEpochMilli(captureTime))
                    .setString(i++, transactionName)
                    .setLong(i++, aggregate.getErrorCount())
                    .setLong(i++, aggregate.getTransactionCount())
                    .setInt(i++, adjustedTTL.generalTTL());
            futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
        }
        return futures;
    }

    @CheckReturnValue
    private List<CompletableFuture<?>> insertQueries(List<Aggregate.Query> queries,
                                                     List<Aggregate.SharedQueryText> sharedQueryTexts, int rollupLevel, String agentRollupId,
                                                     String transactionType, @Nullable String transactionName, long captureTime,
                                                     TTL adjustedTTL) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (Aggregate.Query query : queries) {
            Aggregate.SharedQueryText sharedQueryText =
                    sharedQueryTexts.get(query.getSharedQueryTextIndex());
            BoundStatement boundStatement;
            if (transactionName == null) {
                boundStatement = getInsertOverallPS(queryTable, rollupLevel).bind();
            } else {
                boundStatement = getInsertTransactionPS(queryTable, rollupLevel).bind();
            }
            int i = 0;
            boundStatement = boundStatement.setString(i++, agentRollupId)
                    .setString(i++, transactionType);
            if (transactionName != null) {
                boundStatement = boundStatement.setString(i++, transactionName);
            }
            boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(captureTime))
                    .setString(i++, query.getType());
            String fullTextSha1 = sharedQueryText.getFullTextSha1();
            if (fullTextSha1.isEmpty()) {
                boundStatement = boundStatement.setString(i++, sharedQueryText.getFullText())
                        // full_query_text_sha1 cannot be null since it is used in clustering key
                        .setString(i++, "");
            } else {
                boundStatement = boundStatement.setString(i++, sharedQueryText.getTruncatedText())
                        .setString(i++, fullTextSha1);
            }
            boundStatement = boundStatement.setDouble(i++, query.getTotalDurationNanos())
                    .setLong(i++, query.getExecutionCount());
            if (query.hasTotalRows()) {
                boundStatement = boundStatement.setLong(i++, query.getTotalRows().getValue());
            } else {
                boundStatement = boundStatement.setToNull(i++);
            }
            boundStatement = boundStatement.setInt(i++, adjustedTTL.queryTTL());
            futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
        }
        return futures;
    }

    @CheckReturnValue
    private CompletableFuture<?> insertQueries(List<MutableQuery> queries, int rollupLevel,
                                               String agentRollupId, String transactionType, @Nullable String transactionName,
                                               long captureTime, TTL adjustedTTL) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (MutableQuery query : queries) {
            BoundStatement boundStatement;
            if (transactionName == null) {
                boundStatement = getInsertOverallPS(queryTable, rollupLevel).bind();
            } else {
                boundStatement = getInsertTransactionPS(queryTable, rollupLevel).bind();
            }
            String fullTextSha1 = query.getFullTextSha1();
            int i = 0;
            boundStatement = boundStatement.setString(i++, agentRollupId)
                    .setString(i++, transactionType);
            if (transactionName != null) {
                boundStatement = boundStatement.setString(i++, transactionName);
            }
            boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(captureTime))
                    .setString(i++, query.getType())
                    .setString(i++, query.getTruncatedText())
                    // full_query_text_sha1 cannot be null since it is used in clustering key
                    .setString(i++, Strings.nullToEmpty(fullTextSha1))
                    .setDouble(i++, query.getTotalDurationNanos())
                    .setLong(i++, query.getExecutionCount());
            if (query.hasTotalRows()) {
                boundStatement = boundStatement.setLong(i++, query.getTotalRows());
            } else {
                boundStatement = boundStatement.setToNull(i++);
            }
            boundStatement = boundStatement.setInt(i++, adjustedTTL.queryTTL());
            futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
        }
        return CompletableFutures.allAsList(futures);
    }

    @CheckReturnValue
    private List<CompletableFuture<?>> insertServiceCallsProto(
            List<Aggregate.ServiceCall> serviceCalls, int rollupLevel, String agentRollupId,
            String transactionType, @Nullable String transactionName, long captureTime,
            TTL adjustedTTL) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (Aggregate.ServiceCall serviceCall : serviceCalls) {
            BoundStatement boundStatement;
            if (transactionName == null) {
                boundStatement = getInsertOverallPS(serviceCallTable, rollupLevel).bind();
            } else {
                boundStatement = getInsertTransactionPS(serviceCallTable, rollupLevel).bind();
            }
            int i = 0;
            boundStatement = boundStatement.setString(i++, agentRollupId)
                    .setString(i++, transactionType);
            if (transactionName != null) {
                boundStatement = boundStatement.setString(i++, transactionName);
            }
            boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(captureTime))
                    .setString(i++, serviceCall.getType())
                    .setString(i++, serviceCall.getText())
                    .setDouble(i++, serviceCall.getTotalDurationNanos())
                    .setLong(i++, serviceCall.getExecutionCount())
                    .setInt(i++, adjustedTTL.serviceCallTTL());
            futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
        }
        return futures;
    }

    private CompletableFuture<?> insertServiceCalls(List<MutableServiceCall> serviceCalls,
                                                    int rollupLevel, String agentRollupId, String transactionType,
                                                    @Nullable String transactionName, long captureTime, TTL adjustedTTL) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (MutableServiceCall serviceCall : serviceCalls) {
            BoundStatement boundStatement;
            if (transactionName == null) {
                boundStatement = getInsertOverallPS(serviceCallTable, rollupLevel).bind();
            } else {
                boundStatement = getInsertTransactionPS(serviceCallTable, rollupLevel).bind();
            }
            int i = 0;
            boundStatement = boundStatement.setString(i++, agentRollupId)
                    .setString(i++, transactionType);
            if (transactionName != null) {
                boundStatement = boundStatement.setString(i++, transactionName);
            }
            boundStatement = boundStatement.setInstant(i++, Instant.ofEpochMilli(captureTime))
                    .setString(i++, serviceCall.getType())
                    .setString(i++, serviceCall.getText())
                    .setDouble(i++, serviceCall.getTotalDurationNanos())
                    .setLong(i++, serviceCall.getExecutionCount())
                    .setInt(i++, adjustedTTL.serviceCallTTL());
            futures.add(session.writeAsync(boundStatement, collector).toCompletableFuture());
        }
        return CompletableFutures.allAsList(futures);
    }

    private PreparedStatement getInsertOverallPS(Table table, int rollupLevel) {
        return checkNotNull(insertOverallPS.get(table)).get(rollupLevel);
    }

    private PreparedStatement getInsertTransactionPS(Table table, int rollupLevel) {
        return checkNotNull(insertTransactionPS.get(table)).get(rollupLevel);
    }

    private CompletionStage<AsyncResultSet> executeQuery(String agentRollupId, SummaryQuery query, Table table, CassandraProfile profile) {
        BoundStatement boundStatement =
                checkNotNull(readOverallPS.get(table)).get(query.rollupLevel()).bind();
        boundStatement = bindQuery(boundStatement, agentRollupId, query);
        return session.readAsync(boundStatement, profile);
    }

    private CompletionStage<AsyncResultSet> executeQuery(String agentRollupId, AggregateQuery query, Table table, CassandraProfile profile) {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = checkNotNull(readOverallPS.get(table)).get(query.rollupLevel()).bind();
        } else {
            boundStatement =
                    checkNotNull(readTransactionPS.get(table)).get(query.rollupLevel()).bind();
        }
        boundStatement = bindQuery(boundStatement, agentRollupId, query);
        return session.readAsync(boundStatement, profile);
    }

    private CompletableFuture<AsyncResultSet> executeQueryForRollup(String agentRollupId,
                                                                    AggregateQuery query, Table table, boolean warnIfNoResults) {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement =
                    checkNotNull(readOverallForRollupPS.get(table)).get(query.rollupLevel()).bind();
        } else {
            boundStatement = checkNotNull(readTransactionForRollupPS.get(table))
                    .get(query.rollupLevel()).bind();
        }
        boundStatement = bindQuery(boundStatement, agentRollupId, query);
        if (warnIfNoResults) {
            return session.readAsyncWarnIfNoRows(boundStatement, rollup,
                    "no {} records found for agentRollupId={}, query={}", table.partialName(),
                    agentRollupId, query).toCompletableFuture();
        } else {
            return session.readAsync(boundStatement, rollup).toCompletableFuture();
        }
    }

    private CompletableFuture<AsyncResultSet> executeQueryForRollupFromChild(String childAgentRollupId,
                                                                             AggregateQuery query, Table table, boolean warnIfNoResults) {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = checkNotNull(readOverallForRollupFromChildPS.get(table)).bind();
        } else {
            boundStatement = checkNotNull(readTransactionForRollupFromChildPS.get(table)).bind();
        }
        boundStatement = bindQueryForRollupFromChild(boundStatement, childAgentRollupId, query);
        if (warnIfNoResults) {
            return session.readAsyncWarnIfNoRows(boundStatement, rollup,
                    "no {} records found for agentRollupId={}, query={}", table.partialName(),
                    childAgentRollupId, query).toCompletableFuture();
        } else {
            return session.readAsync(boundStatement, rollup).toCompletableFuture();
        }
    }

    private CompletionStage<?> mergeProfilesInto(String agentRollupId, AggregateQuery query, Table profileTable,
                                                 ProfileCollector collector, CassandraProfile cprofile) {
        Function<AsyncResultSet, CompletableFuture<Void>> compute = new Function<AsyncResultSet, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(AsyncResultSet results) {
                long captureTime = Long.MIN_VALUE;
                for (Row row : results.currentPage()) {
                    captureTime = Math.max(captureTime, checkNotNull(row.getInstant(0)).toEpochMilli());
                    ByteBuffer bytes = checkNotNull(row.getByteBuffer(1));
                    // TODO optimize this byte copying
                    Profile profile = null;
                    try {
                        profile = Profile.parseFrom(bytes);
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException(e);
                    }
                    collector.mergeProfile(profile);
                    collector.updateLastCaptureTime(captureTime);
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return executeQuery(agentRollupId, query, profileTable, cprofile).thenCompose(compute);
    }

    private List<TTL> getTTLs() {
        List<Integer> rollupExpirationHours =
                configRepository.getCentralStorageConfig().rollupExpirationHours();
        List<Integer> queryAndServiceCallRollupExpirationHours =
                configRepository.getCentralStorageConfig()
                        .queryAndServiceCallRollupExpirationHours();
        List<Integer> profileRollupExpirationHours =
                configRepository.getCentralStorageConfig().profileRollupExpirationHours();
        List<TTL> ttls = new ArrayList<>();
        for (int i = 0; i < rollupExpirationHours.size(); i++) {
            ttls.add(ImmutableTTL.builder()
                    .generalTTL(Ints.saturatedCast(HOURS.toSeconds(rollupExpirationHours.get(i))))
                    .queryTTL(Ints.saturatedCast(
                            HOURS.toSeconds(queryAndServiceCallRollupExpirationHours.get(i))))
                    .serviceCallTTL(Ints.saturatedCast(
                            HOURS.toSeconds(queryAndServiceCallRollupExpirationHours.get(i))))
                    .profileTTL(Ints
                            .saturatedCast(HOURS.toSeconds(profileRollupExpirationHours.get(i))))
                    .build());
        }
        return ttls;
    }

    private CompletionStage<RollupParams> getRollupParams(String agentRollupId, String agentRollupIdForMeta,
                                                          int rollupLevel, TTL adjustedTTL) {

        return getMaxQueryAggregatesPerTransactionAggregate(agentRollupIdForMeta)
                .thenCombine(getMaxServiceCallAggregatesPerTransactionAggregate(agentRollupIdForMeta),
                        (maxQueryAggregatesPerTransactionAggregate, maxServiceCallAggregatesPerTransactionAggregate) -> {
                            return ImmutableRollupParams.builder()
                                    .agentRollupId(agentRollupId)
                                    .rollupLevel(rollupLevel)
                                    .adjustedTTL(adjustedTTL)
                                    .maxQueryAggregatesPerTransactionAggregate(maxQueryAggregatesPerTransactionAggregate)
                                    .maxServiceCallAggregatesPerTransactionAggregate(maxServiceCallAggregatesPerTransactionAggregate)
                                    .build();
                        });
    }

    private static String mergeRowIntoSummaries(Row row, Map<String, MutableSummary> summaries) {
        int i = 0;
        String transactionName = checkNotNull(row.getString(i++));
        MutableSummary summary = summaries.get(transactionName);
        if (summary == null) {
            summary = new MutableSummary();
            summaries.put(transactionName, summary);
        }
        summary.totalDurationNanos += row.getDouble(i++);
        summary.totalCpuNanos += row.getDouble(i++);
        summary.totalAllocatedBytes += row.getDouble(i++);
        summary.transactionCount += row.getLong(i++);
        return transactionName;
    }

    @CheckReturnValue
    private static BoundStatement bindAggregateForSummary(BoundStatement boundStatement, Aggregate aggregate,
                                                          int startIndex, TTL adjustedTTL) {
        int i = startIndex;
        boundStatement = boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        double totalCpuNanos = 0;
        double totalAllocatedBytes = 0;
        if (aggregate.hasOldMainThreadStats()) {
            // data from agent prior to 0.10.9
            Aggregate.OldThreadStats mainThreadStats = aggregate.getOldMainThreadStats();
            totalCpuNanos += mainThreadStats.getTotalCpuNanos().getValue();
            totalAllocatedBytes += mainThreadStats.getTotalAllocatedBytes().getValue();
        } else {
            Aggregate.ThreadStats mainThreadStats = aggregate.getMainThreadStats();
            totalCpuNanos += mainThreadStats.getTotalCpuNanos();
            totalAllocatedBytes += mainThreadStats.getTotalAllocatedBytes();
        }
        if (aggregate.hasAuxThreadRootTimer()) {
            if (aggregate.hasOldAuxThreadStats()) {
                // data from agent prior to 0.10.9
                Aggregate.OldThreadStats auxThreadStats = aggregate.getOldAuxThreadStats();
                totalCpuNanos += auxThreadStats.getTotalCpuNanos().getValue();
                totalAllocatedBytes += auxThreadStats.getTotalAllocatedBytes().getValue();
            } else {
                Aggregate.ThreadStats auxThreadStats = aggregate.getAuxThreadStats();
                totalCpuNanos += auxThreadStats.getTotalCpuNanos();
                totalAllocatedBytes += auxThreadStats.getTotalAllocatedBytes();
            }
        }
        return boundStatement.setDouble(i++, totalCpuNanos)
                .setDouble(i++, totalAllocatedBytes)
                .setLong(i++, aggregate.getTransactionCount())
                .setInt(i++, adjustedTTL.generalTTL());
    }

    @CheckReturnValue
    private static BoundStatement bindAggregate(BoundStatement boundStatement, Aggregate aggregate,
                                                int startIndex, TTL adjustedTTL) {
        int i = startIndex;
        boundStatement = boundStatement.setDouble(i++, aggregate.getTotalDurationNanos())
                .setLong(i++, aggregate.getTransactionCount())
                .setBoolean(i++, aggregate.getAsyncTransactions())
                .setByteBuffer(i++, Messages.toByteBuffer(aggregate.getMainThreadRootTimerList()));
        if (aggregate.hasOldMainThreadStats()) {
            // data from agent prior to 0.10.9
            Aggregate.OldThreadStats mainThreadStats = aggregate.getOldMainThreadStats();
            boundStatement = boundStatement.setDouble(i++, mainThreadStats.getTotalCpuNanos().getValue())
                    .setDouble(i++, mainThreadStats.getTotalBlockedNanos().getValue())
                    .setDouble(i++, mainThreadStats.getTotalWaitedNanos().getValue())
                    .setDouble(i++, mainThreadStats.getTotalAllocatedBytes().getValue());
        } else {
            Aggregate.ThreadStats mainThreadStats = aggregate.getMainThreadStats();
            boundStatement = boundStatement.setDouble(i++, mainThreadStats.getTotalCpuNanos())
                    .setDouble(i++, mainThreadStats.getTotalBlockedNanos())
                    .setDouble(i++, mainThreadStats.getTotalWaitedNanos())
                    .setDouble(i++, mainThreadStats.getTotalAllocatedBytes());
        }
        if (aggregate.hasAuxThreadRootTimer()) {
            // writing as delimited singleton list for backwards compatibility with data written
            // prior to 0.12.0
            boundStatement = boundStatement.setByteBuffer(i++,
                    Messages.toByteBuffer(ImmutableList.of(aggregate.getAuxThreadRootTimer())));
            if (aggregate.hasOldAuxThreadStats()) {
                Aggregate.OldThreadStats auxThreadStats = aggregate.getOldAuxThreadStats();
                boundStatement = boundStatement.setDouble(i++, auxThreadStats.getTotalCpuNanos().getValue())
                        .setDouble(i++, auxThreadStats.getTotalBlockedNanos().getValue())
                        .setDouble(i++, auxThreadStats.getTotalWaitedNanos().getValue())
                        .setDouble(i++, auxThreadStats.getTotalAllocatedBytes().getValue());
            } else {
                Aggregate.ThreadStats auxThreadStats = aggregate.getAuxThreadStats();
                boundStatement = boundStatement.setDouble(i++, auxThreadStats.getTotalCpuNanos())
                        .setDouble(i++, auxThreadStats.getTotalBlockedNanos())
                        .setDouble(i++, auxThreadStats.getTotalWaitedNanos())
                        .setDouble(i++, auxThreadStats.getTotalAllocatedBytes());
            }
        } else {
            boundStatement = boundStatement.setToNull(i++)
                    .setToNull(i++)
                    .setToNull(i++)
                    .setToNull(i++)
                    .setToNull(i++);
        }
        List<Aggregate.Timer> asyncTimers = aggregate.getAsyncTimerList();
        if (asyncTimers.isEmpty()) {
            boundStatement = boundStatement.setToNull(i++);
        } else {
            boundStatement = boundStatement.setByteBuffer(i++, Messages.toByteBuffer(asyncTimers));
        }
        return boundStatement.setInt(i++, adjustedTTL.generalTTL());
    }

    @CheckReturnValue
    private static BoundStatement bindQuery(BoundStatement boundStatement, String agentRollupId,
                                            SummaryQuery query) {
        int i = 0;
        return boundStatement.setString(i++, agentRollupId)
                .setString(i++, query.transactionType())
                .setInstant(i++, Instant.ofEpochMilli(query.from()))
                .setInstant(i++, Instant.ofEpochMilli(query.to()));
    }

    @CheckReturnValue
    private static BoundStatement bindQuery(BoundStatement boundStatement, String agentRollupId,
                                            AggregateQuery query) {
        int i = 0;
        boundStatement = boundStatement.setString(i++, agentRollupId)
                .setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            boundStatement = boundStatement.setString(i++, transactionName);
        }
        return boundStatement.setInstant(i++, Instant.ofEpochMilli(query.from()))
                .setInstant(i++, Instant.ofEpochMilli(query.to()));
    }

    @CheckReturnValue
    private static BoundStatement bindQueryForRollupFromChild(BoundStatement boundStatement,
                                                              String agentRollupId, AggregateQuery query) {
        int i = 0;
        boundStatement = boundStatement.setString(i++, agentRollupId)
                .setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            boundStatement = boundStatement.setString(i++, transactionName);
        }
        return boundStatement.setInstant(i++, Instant.ofEpochMilli(query.to()));
    }

    private static List<Aggregate.Query> getQueries(Aggregate aggregate) {
        List<Aggregate.OldQueriesByType> queriesByTypeList = aggregate.getOldQueriesByTypeList();
        if (queriesByTypeList.isEmpty()) {
            return aggregate.getQueryList();
        }
        List<Aggregate.Query> queries = new ArrayList<>();
        for (Aggregate.OldQueriesByType queriesByType : queriesByTypeList) {
            for (Aggregate.OldQuery query : queriesByType.getQueryList()) {
                queries.add(Aggregate.Query.newBuilder()
                        .setType(queriesByType.getType())
                        .setSharedQueryTextIndex(query.getSharedQueryTextIndex())
                        .setTotalDurationNanos(query.getTotalDurationNanos())
                        .setExecutionCount(query.getExecutionCount())
                        .setTotalRows(query.getTotalRows())
                        .build());
            }
        }
        return queries;
    }

    private static List<Aggregate.ServiceCall> getServiceCalls(Aggregate aggregate) {
        List<Aggregate.OldServiceCallsByType> serviceCallsByTypeList =
                aggregate.getOldServiceCallsByTypeList();
        if (serviceCallsByTypeList.isEmpty()) {
            return aggregate.getServiceCallList();
        }
        List<Aggregate.ServiceCall> serviceCalls = new ArrayList<>();
        for (Aggregate.OldServiceCallsByType serviceCallsByType : serviceCallsByTypeList) {
            for (Aggregate.OldServiceCall serviceCall : serviceCallsByType.getServiceCallList()) {
                serviceCalls.add(Aggregate.ServiceCall.newBuilder()
                        .setType(serviceCallsByType.getType())
                        .setText(serviceCall.getText())
                        .setTotalDurationNanos(serviceCall.getTotalDurationNanos())
                        .setExecutionCount(serviceCall.getExecutionCount())
                        .build());
            }
        }
        return serviceCalls;
    }

    private static double getNextThreadStat(Row row, int columnIndex) {
        Double threadStat = row.get(columnIndex, Double.class);
        if (threadStat == null) {
            // old data stored prior to 0.10.9
            return NotAvailableAware.NA;
        } else {
            return threadStat;
        }
    }

    private static String createTableQuery(Table table, boolean transaction, int i) {
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
        sb.append(", primary key ((agent_rollup, transaction_type");
        if (transaction) {
            sb.append(", transaction_name");
        }
        sb.append("), capture_time");
        for (String clusterKey : table.clusterKey()) {
            sb.append(", ");
            sb.append(clusterKey);
        }
        sb.append("))");
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

    private static String readForRollupFromChildPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select ");
        appendColumnNames(sb, table.columns());
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ?");
        if (transaction) {
            sb.append(" and transaction_name = ?");
        }
        sb.append(" and capture_time = ?");
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

    private static String createSummaryTableQuery(Table table, boolean transaction, int i) {
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
        sb.append(", primary key ((agent_rollup, transaction_type), capture_time");
        if (transaction) {
            sb.append(", transaction_name");
        }
        sb.append("))");
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
        sb.append(" where agent_rollup = ? and transaction_type = ? and capture_time > ?");
        sb.append(" and capture_time <= ?");
        return sb.toString();
    }

    private static String readSummaryForRollupFromChildPS(Table table, boolean transaction,
                                                          int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select ");
        if (transaction) {
            sb.append("transaction_name, ");
        }
        appendColumnNames(sb, table.columns());
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where agent_rollup = ? and transaction_type = ? and capture_time = ?");
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

    private static ByteBuffer toByteBuffer(AbstractMessage message) {
        return ByteBuffer.wrap(message.toByteArray());
    }

    private CompletionStage<Integer> getMaxQueryAggregatesPerTransactionAggregate(String agentRollupId) {
        return configRepository.getAdvancedConfig(agentRollupId).thenApply(advancedConfig -> {
            if (advancedConfig == null) {
                return ConfigDefaults.ADVANCED_MAX_QUERY_AGGREGATES;
            }
            if (advancedConfig.hasMaxQueryAggregates()) {
                return advancedConfig.getMaxQueryAggregates().getValue();
            } else {
                return ConfigDefaults.ADVANCED_MAX_QUERY_AGGREGATES;
            }
        });
    }

    private CompletionStage<Integer> getMaxServiceCallAggregatesPerTransactionAggregate(String agentRollupId) {
        return configRepository.getAdvancedConfig(agentRollupId).thenApply(advancedConfig -> {
            if (advancedConfig == null) {
                return ConfigDefaults.ADVANCED_MAX_SERVICE_CALL_AGGREGATES;
            }
            if (advancedConfig.hasMaxServiceCallAggregates()) {
                return advancedConfig.getMaxServiceCallAggregates().getValue();
            } else {
                return ConfigDefaults.ADVANCED_MAX_SERVICE_CALL_AGGREGATES;
            }
        });
    }

    static TTL getAdjustedTTL(TTL ttl, long captureTime, Clock clock) {
        return ImmutableTTL.builder()
                .generalTTL(Common.getAdjustedTTL(ttl.generalTTL(), captureTime, clock))
                .queryTTL(Common.getAdjustedTTL(ttl.queryTTL(), captureTime, clock))
                .serviceCallTTL(Common.getAdjustedTTL(ttl.serviceCallTTL(), captureTime, clock))
                .profileTTL(Common.getAdjustedTTL(ttl.profileTTL(), captureTime, clock))
                .build();
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
        String agentRollupId();

        int rollupLevel();

        TTL adjustedTTL();

        int maxQueryAggregatesPerTransactionAggregate();

        int maxServiceCallAggregatesPerTransactionAggregate();
    }

    @Value.Immutable
    interface TTL {
        int generalTTL();

        int queryTTL();

        int serviceCallTTL();

        int profileTTL();
    }

    private static class MutableSummary {
        private double totalDurationNanos;
        private long transactionCount;
        private double totalCpuNanos;
        private double totalAllocatedBytes;
    }

    private static class MutableErrorSummary {
        private long errorCount;
        private long transactionCount;
    }

}
