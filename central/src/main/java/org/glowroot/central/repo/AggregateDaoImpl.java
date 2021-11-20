/*
 * Copyright 2015-2019 the original author or authors.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.AbstractMessage;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.central.repo.Common.NeedsRollup;
import org.glowroot.central.repo.Common.NeedsRollupFromChildren;
import org.glowroot.central.util.Messages;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.MoreFutures.DoRollup;
import org.glowroot.central.util.Session;
import org.glowroot.common.ConfigDefaults;
import org.glowroot.common.Constants;
import org.glowroot.common.live.ImmutableAggregateQuery;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.AggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.SummaryQuery;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
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
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Styles;
import org.glowroot.common2.repo.ConfigRepository.AgentConfigNotFoundException;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.MutableAggregate;
import org.glowroot.common2.repo.MutableThreadStats;
import org.glowroot.common2.repo.MutableTimer;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OldTransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.HOURS;

public class AggregateDaoImpl implements AggregateDao {

    @SuppressWarnings("deprecation")
    private static final HashFunction SHA_1 = Hashing.sha1();

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

    @Override
    public void store(String agentId, long captureTime,
            List<OldAggregatesByType> aggregatesByTypeList,
            List<Aggregate.SharedQueryText> initialSharedQueryTexts) throws Exception {
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentId);
        store(agentId, agentRollupIds, agentId, agentRollupIds, captureTime, aggregatesByTypeList,
                initialSharedQueryTexts);
    }

    public void store(String agentId, List<String> agentRollupIds,
            String agentIdForMeta, List<String> agentRollupIdsForMeta, long captureTime,
            List<OldAggregatesByType> aggregatesByTypeList,
            List<Aggregate.SharedQueryText> initialSharedQueryTexts) throws Exception {
        if (aggregatesByTypeList.isEmpty()) {
            MoreFutures.waitForAll(activeAgentDao.insert(agentIdForMeta, captureTime));
            return;
        }
        TTL adjustedTTL = getAdjustedTTL(getTTLs().get(0), captureTime, clock);
        List<Future<?>> futures = new ArrayList<>();
        List<Aggregate.SharedQueryText> sharedQueryTexts = new ArrayList<>();
        for (Aggregate.SharedQueryText sharedQueryText : initialSharedQueryTexts) {
            String fullTextSha1 = sharedQueryText.getFullTextSha1();
            if (fullTextSha1.isEmpty()) {
                String fullText = sharedQueryText.getFullText();
                if (fullText.length() > Constants.AGGREGATE_QUERY_TEXT_TRUNCATE) {
                    // relying on agent side to rate limit (re-)sending the same full text
                    fullTextSha1 = SHA_1.hashString(fullText, UTF_8).toString();
                    futures.addAll(fullQueryTextDao.store(agentRollupIds, fullTextSha1, fullText));
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
        MoreFutures.waitForAll(futures);
        futures.clear();

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
            // wait for success before proceeding in order to ensure cannot end up with
            // "no overview table records found" during a transactionName rollup, since
            // transactionName rollups are based on finding transactionName in summary table
            MoreFutures.waitForAll(futures);
            futures.clear();
            for (OldTransactionAggregate transactionAggregate : aggregatesByType
                    .getTransactionAggregateList()) {
                futures.addAll(storeTransactionNameSummary(agentId, transactionType,
                        transactionAggregate.getTransactionName(), captureTime,
                        transactionAggregate.getAggregate(), adjustedTTL));
            }
            futures.addAll(transactionTypeDao.store(agentRollupIdsForMeta, transactionType));
        }
        futures.addAll(activeAgentDao.insert(agentIdForMeta, captureTime));
        // wait for success before inserting "needs rollup" records
        MoreFutures.waitForAll(futures);
        futures.clear();

        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        // TODO report checker framework issue that occurs without this suppression
        @SuppressWarnings("assignment.type.incompatible")
        Set<String> transactionTypes = aggregatesByTypeList.stream()
                .map(OldAggregatesByType::getTransactionType).collect(Collectors.toSet());

        int needsRollupAdjustedTTL =
                Common.getNeedsRollupAdjustedTTL(adjustedTTL.generalTTL(), rollupConfigs);
        if (agentRollupIds.size() > 1) {
            BoundStatement boundStatement = insertNeedsRollupFromChild.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupIds.get(1));
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setUUID(i++, UUIDs.timeBased());
            boundStatement.setString(i++, agentId);
            boundStatement.setSet(i++, transactionTypes);
            boundStatement.setInt(i++, needsRollupAdjustedTTL);
            futures.add(session.writeAsync(boundStatement));
        }
        // insert into aggregate_needs_rollup_1
        long intervalMillis = rollupConfigs.get(1).intervalMillis();
        long rollupCaptureTime = CaptureTimes.getRollup(captureTime, intervalMillis);
        BoundStatement boundStatement = insertNeedsRollup.get(0).bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setTimestamp(i++, new Date(rollupCaptureTime));
        boundStatement.setUUID(i++, UUIDs.timeBased());
        boundStatement.setSet(i++, transactionTypes);
        boundStatement.setInt(i++, needsRollupAdjustedTTL);
        futures.add(session.writeAsync(boundStatement));
        MoreFutures.waitForAll(futures);
    }

    // query.from() is non-inclusive
    @Override
    public void mergeOverallSummaryInto(String agentRollupId, SummaryQuery query,
            OverallSummaryCollector collector) throws Exception {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        ResultSet results = executeQuery(agentRollupId, query, summaryTable);
        for (Row row : results) {
            int i = 0;
            // results are ordered by capture time so Math.max() is not needed here
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            collector.mergeSummary(totalDurationNanos, transactionCount, captureTime);
        }
    }

    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionNameSummaryCollector
    //
    // query.from() is non-inclusive
    @Override
    public void mergeTransactionNameSummariesInto(String agentRollupId, SummaryQuery query,
            SummarySortOrder sortOrder, int limit, TransactionNameSummaryCollector collector)
            throws Exception {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement =
                checkNotNull(readTransactionPS.get(summaryTable)).get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollupId, query);
        ResultSet results = session.read(boundStatement);
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            String transactionName = checkNotNull(row.getString(i++));
            double totalDurationNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            collector.collect(transactionName, totalDurationNanos, transactionCount, captureTime);
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeOverallErrorSummaryInto(String agentRollupId, SummaryQuery query,
            OverallErrorSummaryCollector collector) throws Exception {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        ResultSet results = executeQuery(agentRollupId, query, errorSummaryTable);
        for (Row row : results) {
            int i = 0;
            // results are ordered by capture time so Math.max() is not needed here
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            long errorCount = row.getLong(i++);
            long transactionCount = row.getLong(i++);
            collector.mergeErrorSummary(errorCount, transactionCount, captureTime);
        }
    }

    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionNameErrorSummaryCollector
    //
    // query.from() is non-inclusive
    @Override
    public void mergeTransactionNameErrorSummariesInto(String agentRollupId, SummaryQuery query,
            ErrorSummarySortOrder sortOrder, int limit,
            TransactionNameErrorSummaryCollector collector) throws Exception {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement = checkNotNull(readTransactionPS.get(errorSummaryTable))
                .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollupId, query);
        ResultSet results = session.read(boundStatement);
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            String transactionName = checkNotNull(row.getString(i++));
            long errorCount = row.getLong(i++);
            long transactionCount = row.getLong(i++);
            collector.collect(transactionName, errorCount, transactionCount, captureTime);
        }
    }

    // query.from() is INCLUSIVE
    @Override
    public List<OverviewAggregate> readOverviewAggregates(String agentRollupId,
            AggregateQuery query) throws Exception {
        ResultSet results = executeQuery(agentRollupId, query, overviewTable);
        List<OverviewAggregate> overviewAggregates = new ArrayList<>();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            boolean asyncTransactions = row.getBool(i++);
            List<Aggregate.Timer> mainThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            Aggregate.ThreadStats mainThreadStats = Aggregate.ThreadStats.newBuilder()
                    .setTotalCpuNanos(getNextThreadStat(row, i++))
                    .setTotalBlockedNanos(getNextThreadStat(row, i++))
                    .setTotalWaitedNanos(getNextThreadStat(row, i++))
                    .setTotalAllocatedBytes(getNextThreadStat(row, i++))
                    .build();
            // reading delimited singleton list for backwards compatibility with data written
            // prior to 0.12.0
            List<Aggregate.Timer> list =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
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
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
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
        return overviewAggregates;
    }

    // query.from() is INCLUSIVE
    @Override
    public List<PercentileAggregate> readPercentileAggregates(String agentRollupId,
            AggregateQuery query) throws Exception {
        ResultSet results = executeQuery(agentRollupId, query, histogramTable);
        List<PercentileAggregate> percentileAggregates = new ArrayList<>();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            ByteBuffer bytes = checkNotNull(row.getBytes(i++));
            Aggregate.Histogram durationNanosHistogram = Aggregate.Histogram.parseFrom(bytes);
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
    public List<ThroughputAggregate> readThroughputAggregates(String agentRollupId,
            AggregateQuery query) throws Exception {
        ResultSet results = executeQuery(agentRollupId, query, throughputTable);
        List<ThroughputAggregate> throughputAggregates = new ArrayList<>();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            long transactionCount = row.getLong(i++);
            boolean hasErrorCount = !row.isNull(i);
            long errorCount = row.getLong(i++);
            throughputAggregates.add(ImmutableThroughputAggregate.builder()
                    .captureTime(captureTime)
                    .transactionCount(transactionCount)
                    .errorCount(hasErrorCount ? errorCount : null)
                    .build());
        }
        return throughputAggregates;
    }

    // query.from() is non-inclusive
    @Override
    public void mergeQueriesInto(String agentRollupId, AggregateQuery query,
            QueryCollector collector) throws Exception {
        ResultSet results = executeQuery(agentRollupId, query, queryTable);
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
    public void mergeServiceCallsInto(String agentRollupId, AggregateQuery query,
            ServiceCallCollector collector) throws Exception {
        ResultSet results = executeQuery(agentRollupId, query, serviceCallTable);
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
    public void mergeMainThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector) throws Exception {
        mergeProfilesInto(agentRollupId, query, mainThreadProfileTable, collector);
    }

    // query.from() is non-inclusive
    @Override
    public void mergeAuxThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector) throws Exception {
        mergeProfilesInto(agentRollupId, query, auxThreadProfileTable, collector);
    }

    @Override
    public @Nullable String readFullQueryText(String agentRollupId, String fullQueryTextSha1)
            throws Exception {
        return fullQueryTextDao.getFullText(agentRollupId, fullQueryTextSha1);
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasMainThreadProfile(String agentRollupId, AggregateQuery query)
            throws Exception {
        BoundStatement boundStatement = query.transactionName() == null
                ? existsMainThreadProfileOverallPS.get(query.rollupLevel()).bind()
                : existsMainThreadProfileTransactionPS.get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollupId, query);
        ResultSet results = session.read(boundStatement);
        return results.one() != null;
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasAuxThreadProfile(String agentRollupId, AggregateQuery query)
            throws Exception {
        BoundStatement boundStatement = query.transactionName() == null
                ? existsAuxThreadProfileOverallPS.get(query.rollupLevel()).bind()
                : existsAuxThreadProfileTransactionPS.get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollupId, query);
        ResultSet results = session.read(boundStatement);
        return results.one() != null;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveQueries(String agentRollupId, AggregateQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveServiceCalls(String agentRollupId, AggregateQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveMainThreadProfile(String agentRollupId, AggregateQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveAuxThreadProfile(String agentRollupId, AggregateQuery query) {
        // TODO (this is only used for displaying data expired message)
        return false;
    }

    @Override
    public void rollup(String agentRollupId) throws Exception {
        rollup(agentRollupId, agentRollupId, AgentRollupIds.getParent(agentRollupId),
                !agentRollupId.endsWith("::"));
    }

    public void rollup(String agentRollupId, String agentRollupIdForMeta,
            @Nullable String parentAgentRollupId, boolean leaf) throws Exception {
        List<TTL> ttls = getTTLs();
        if (!leaf) {
            rollupFromChildren(agentRollupId, agentRollupIdForMeta, parentAgentRollupId,
                    ttls.get(0));
        }
        int rollupLevel = 1;
        while (rollupLevel < configRepository.getRollupConfigs().size()) {
            TTL ttl = ttls.get(rollupLevel);
            rollup(agentRollupId, agentRollupIdForMeta, rollupLevel, ttl);
            rollupLevel++;
        }
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

    private void rollupFromChildren(String agentRollupId, String agentRollupIdForMeta,
            @Nullable String parentAgentRollupId, TTL ttl) throws Exception {
        final int rollupLevel = 0;
        List<NeedsRollupFromChildren> needsRollupFromChildrenList = Common
                .getNeedsRollupFromChildrenList(agentRollupId, readNeedsRollupFromChild, session);
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long nextRollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
        for (NeedsRollupFromChildren needsRollupFromChildren : needsRollupFromChildrenList) {
            long captureTime = needsRollupFromChildren.getCaptureTime();
            TTL adjustedTTL = getAdjustedTTL(ttl, captureTime, clock);
            RollupParams rollupParams =
                    getRollupParams(agentRollupId, agentRollupIdForMeta, rollupLevel, adjustedTTL);
            List<Future<?>> futures = new ArrayList<>();
            for (Map.Entry<String, Collection<String>> entry : needsRollupFromChildren.getKeys()
                    .asMap()
                    .entrySet()) {
                String transactionType = entry.getKey();
                Collection<String> childAgentRollupIds = entry.getValue();
                futures.addAll(rollupOneFromChildren(rollupParams, transactionType,
                        childAgentRollupIds, captureTime));
            }
            // wait for above async work to ensure rollup complete before proceeding
            MoreFutures.waitForAll(futures);

            int needsRollupAdjustedTTL =
                    Common.getNeedsRollupAdjustedTTL(adjustedTTL.generalTTL(), rollupConfigs);
            if (parentAgentRollupId != null) {
                // insert needs to happen first before call to postRollup(), see method-level
                // comment on postRollup
                Common.insertNeedsRollupFromChild(agentRollupId, parentAgentRollupId,
                        insertNeedsRollupFromChild, needsRollupFromChildren, captureTime,
                        needsRollupAdjustedTTL, session);
            }
            Common.postRollup(agentRollupId, needsRollupFromChildren.getCaptureTime(),
                    needsRollupFromChildren.getKeys().keySet(),
                    needsRollupFromChildren.getUniquenessKeysForDeletion(),
                    nextRollupIntervalMillis, insertNeedsRollup.get(rollupLevel),
                    deleteNeedsRollupFromChild, needsRollupAdjustedTTL, session);
        }
    }

    private void rollup(String agentRollupId, String agentRollupIdForMeta, int rollupLevel, TTL ttl)
            throws Exception {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long rollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
        Collection<NeedsRollup> needsRollupList = Common.getNeedsRollupList(agentRollupId,
                rollupLevel, rollupIntervalMillis, readNeedsRollup, session, clock);
        Long nextRollupIntervalMillis = null;
        if (rollupLevel + 1 < rollupConfigs.size()) {
            nextRollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
        }
        for (NeedsRollup needsRollup : needsRollupList) {
            long captureTime = needsRollup.getCaptureTime();
            TTL adjustedTTL = getAdjustedTTL(ttl, captureTime, clock);
            int needsRollupAdjustedTTL =
                    Common.getNeedsRollupAdjustedTTL(adjustedTTL.generalTTL(), rollupConfigs);
            RollupParams rollupParams =
                    getRollupParams(agentRollupId, agentRollupIdForMeta, rollupLevel, adjustedTTL);
            long from = captureTime - rollupIntervalMillis;
            Set<String> transactionTypes = needsRollup.getKeys();
            List<Future<?>> futures = new ArrayList<>();
            for (String transactionType : transactionTypes) {
                futures.addAll(rollupOne(rollupParams, transactionType, from, captureTime));
            }
            if (futures.isEmpty()) {
                // no rollups occurred, warning already logged inside rollupOne() above
                // this can happen there is an old "needs rollup" record that was created prior to
                // TTL was introduced in 0.9.6, and when the "last needs rollup" record wasn't
                // processed (also prior to 0.9.6), and when the corresponding old data has expired
                Common.postRollup(agentRollupId, needsRollup.getCaptureTime(), transactionTypes,
                        needsRollup.getUniquenessKeysForDeletion(), null, null,
                        deleteNeedsRollup.get(rollupLevel - 1), -1, session);
                continue;
            }
            // wait for above async work to ensure rollup complete before proceeding
            MoreFutures.waitForAll(futures);

            PreparedStatement insertNeedsRollup = nextRollupIntervalMillis == null ? null
                    : this.insertNeedsRollup.get(rollupLevel);
            PreparedStatement deleteNeedsRollup = this.deleteNeedsRollup.get(rollupLevel - 1);
            Common.postRollup(agentRollupId, needsRollup.getCaptureTime(), transactionTypes,
                    needsRollup.getUniquenessKeysForDeletion(), nextRollupIntervalMillis,
                    insertNeedsRollup, deleteNeedsRollup, needsRollupAdjustedTTL, session);
        }
    }

    private List<Future<?>> rollupOneFromChildren(RollupParams rollup, String transactionType,
            Collection<String> childAgentRollupIds, long captureTime) throws Exception {

        ImmutableAggregateQuery query = ImmutableAggregateQuery.builder()
                .transactionType(transactionType)
                .from(captureTime)
                .to(captureTime)
                .rollupLevel(rollup.rollupLevel()) // rolling up from same level (which is always 0)
                .build();
        List<Future<?>> futures = new ArrayList<>();

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

    private List<Future<?>> rollupOne(RollupParams rollup, String transactionType, long from,
            long to) throws Exception {

        ImmutableAggregateQuery query = ImmutableAggregateQuery.builder()
                .transactionType(transactionType)
                .from(from)
                .to(to)
                .rollupLevel(rollup.rollupLevel() - 1)
                .build();
        List<Future<?>> futures = new ArrayList<>();

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

    private List<Future<?>> rollupOtherParts(RollupParams rollup, AggregateQuery query,
            ScratchBuffer scratchBuffer) throws Exception {
        List<Future<?>> futures = new ArrayList<>();
        futures.add(rollupOverview(rollup, query));
        futures.add(rollupHistogram(rollup, query, scratchBuffer));
        futures.add(rollupThroughput(rollup, query));
        futures.add(rollupQueries(rollup, query));
        futures.add(rollupServiceCalls(rollup, query));
        futures.add(rollupThreadProfile(rollup, query, mainThreadProfileTable));
        futures.add(rollupThreadProfile(rollup, query, auxThreadProfileTable));
        return futures;
    }

    private List<Future<?>> rollupOtherPartsFromChildren(RollupParams rollup,
            AggregateQuery query, Collection<String> childAgentRollupIds,
            ScratchBuffer scratchBuffer) throws Exception {
        List<Future<?>> futures = new ArrayList<>();
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

    private ListenableFuture<?> rollupOverallSummary(RollupParams rollup, AggregateQuery query)
            throws Exception {
        ListenableFuture<ResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, summaryTable, true);
        return MoreFutures.rollupAsync(future, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupOverallSummaryFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupOverallSummaryFromChildren(RollupParams rollup,
            AggregateQuery query, Collection<String> childAgentRollupIds) throws Exception {
        List<ListenableFuture<ResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, summaryTable, true);
        return MoreFutures.rollupAsync(futures, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupOverallSummaryFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupOverallSummaryFromRows(RollupParams rollup,
            AggregateQuery query, Iterable<Row> rows) throws Exception {
        double totalDurationNanos = 0;
        long transactionCount = 0;
        for (Row row : rows) {
            totalDurationNanos += row.getDouble(0);
            transactionCount += row.getLong(1);
        }
        BoundStatement boundStatement =
                getInsertOverallPS(summaryTable, rollup.rollupLevel()).bind();
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setInt(i++, rollup.adjustedTTL().generalTTL());
        return session.writeAsync(boundStatement);
    }

    private ListenableFuture<?> rollupErrorSummary(RollupParams rollup, AggregateQuery query)
            throws Exception {
        ListenableFuture<ResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, errorSummaryTable, false);
        return MoreFutures.rollupAsync(future, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupErrorSummaryFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupErrorSummaryFromChildren(RollupParams rollup,
            AggregateQuery query, Collection<String> childAgentRollupIds) throws Exception {
        List<ListenableFuture<ResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, errorSummaryTable, false);
        return MoreFutures.rollupAsync(futures, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupErrorSummaryFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupErrorSummaryFromRows(RollupParams rollup,
            AggregateQuery query, Iterable<Row> rows) throws Exception {
        long errorCount = 0;
        long transactionCount = 0;
        for (Row row : rows) {
            errorCount += row.getLong(0);
            transactionCount += row.getLong(1);
        }
        BoundStatement boundStatement =
                getInsertOverallPS(errorSummaryTable, rollup.rollupLevel()).bind();
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setLong(i++, errorCount);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setInt(i++, rollup.adjustedTTL().generalTTL());
        return session.writeAsync(boundStatement);
    }

    // transactionNames is passed in empty, and populated synchronously by this method
    private ListenableFuture<?> rollupTransactionSummary(RollupParams rollup,
            AggregateQuery query, Set<String> transactionNames) throws Exception {
        BoundStatement boundStatement = checkNotNull(readTransactionForRollupPS.get(summaryTable))
                .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, rollup.agentRollupId(), query);
        // need to populate transactionNames synchronously before returning from this method
        ResultSet results = session.read(boundStatement);
        Map<String, MutableSummary> summaries = new HashMap<>();
        for (Row row : results) {
            String transactionName = mergeRowIntoSummaries(row, summaries);
            transactionNames.add(transactionName);
        }
        return insertTransactionSummaries(rollup, query, summaries);
    }

    // transactionNames is passed in empty, and populated synchronously by this method
    private ListenableFuture<?> rollupTransactionSummaryFromChildren(RollupParams rollup,
            AggregateQuery query, Collection<String> childAgentRollupIds,
            Multimap<String, String> transactionNames) throws Exception {
        Map<String, ListenableFuture<ResultSet>> futures =
                getRowsForSummaryRollupFromChildren(query, childAgentRollupIds, summaryTable, true);
        Map<String, MutableSummary> summaries = new HashMap<>();
        for (Map.Entry<String, ListenableFuture<ResultSet>> entry : futures.entrySet()) {
            String childAgentRollupId = entry.getKey();
            ResultSet results = entry.getValue().get();
            for (Row row : results) {
                String transactionName = mergeRowIntoSummaries(row, summaries);
                transactionNames.put(transactionName, childAgentRollupId);
            }
        }
        return insertTransactionSummaries(rollup, query, summaries);
    }

    private ListenableFuture<?> insertTransactionSummaries(RollupParams rollup,
            AggregateQuery query, Map<String, MutableSummary> summaries) throws Exception {
        BoundStatement boundStatement;
        List<ListenableFuture<?>> futures = new ArrayList<>();
        PreparedStatement preparedStatement =
                getInsertTransactionPS(summaryTable, rollup.rollupLevel());
        for (Map.Entry<String, MutableSummary> entry : summaries.entrySet()) {
            MutableSummary summary = entry.getValue();
            boundStatement = preparedStatement.bind();
            int i = 0;
            boundStatement.setString(i++, rollup.agentRollupId());
            boundStatement.setString(i++, query.transactionType());
            boundStatement.setTimestamp(i++, new Date(query.to()));
            boundStatement.setString(i++, entry.getKey());
            boundStatement.setDouble(i++, summary.totalDurationNanos);
            boundStatement.setLong(i++, summary.transactionCount);
            boundStatement.setInt(i++, rollup.adjustedTTL().generalTTL());
            futures.add(session.writeAsync(boundStatement));
        }
        return Futures.allAsList(futures);
    }

    private ListenableFuture<?> rollupTransactionErrorSummary(RollupParams rollup,
            AggregateQuery query) throws Exception {
        BoundStatement boundStatement =
                checkNotNull(readTransactionForRollupPS.get(errorSummaryTable))
                        .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, rollup.agentRollupId(), query);
        ListenableFuture<ResultSet> future = session.readAsync(boundStatement);
        return MoreFutures.rollupAsync(future, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupTransactionErrorSummaryFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupTransactionErrorSummaryFromChildren(RollupParams rollup,
            AggregateQuery query, Collection<String> childAgentRollupIds) throws Exception {
        Map<String, ListenableFuture<ResultSet>> futures = getRowsForSummaryRollupFromChildren(
                query, childAgentRollupIds, errorSummaryTable, false);
        return MoreFutures.rollupAsync(futures.values(), asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupTransactionErrorSummaryFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupTransactionErrorSummaryFromRows(RollupParams rollup,
            AggregateQuery query, Iterable<Row> rows) throws Exception {
        BoundStatement boundStatement;
        Map<String, MutableErrorSummary> summaries = new HashMap<>();
        for (Row row : rows) {
            int i = 0;
            String transactionName = checkNotNull(row.getString(i++));
            MutableErrorSummary summary = summaries.get(transactionName);
            if (summary == null) {
                summary = new MutableErrorSummary();
                summaries.put(transactionName, summary);
            }
            summary.errorCount += row.getLong(i++);
            summary.transactionCount += row.getLong(i++);
        }
        PreparedStatement preparedStatement =
                getInsertTransactionPS(errorSummaryTable, rollup.rollupLevel());
        List<ListenableFuture<?>> futures = new ArrayList<>();
        for (Map.Entry<String, MutableErrorSummary> entry : summaries.entrySet()) {
            MutableErrorSummary summary = entry.getValue();
            boundStatement = preparedStatement.bind();
            int i = 0;
            boundStatement.setString(i++, rollup.agentRollupId());
            boundStatement.setString(i++, query.transactionType());
            boundStatement.setTimestamp(i++, new Date(query.to()));
            boundStatement.setString(i++, entry.getKey());
            boundStatement.setLong(i++, summary.errorCount);
            boundStatement.setLong(i++, summary.transactionCount);
            boundStatement.setInt(i++, rollup.adjustedTTL().generalTTL());
            futures.add(session.writeAsync(boundStatement));
        }
        return Futures.allAsList(futures);
    }

    private ListenableFuture<?> rollupOverview(RollupParams rollup, AggregateQuery query)
            throws Exception {
        ListenableFuture<ResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, overviewTable, true);
        return MoreFutures.rollupAsync(future, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupOverviewFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupOverviewFromChildren(RollupParams rollup,
            AggregateQuery query, Collection<String> childAgentRollupIds) throws Exception {
        List<ListenableFuture<ResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, overviewTable, true);
        return MoreFutures.rollupAsync(futures, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupOverviewFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupOverviewFromRows(RollupParams rollup, AggregateQuery query,
            Iterable<Row> rows) throws Exception {
        double totalDurationNanos = 0;
        long transactionCount = 0;
        boolean asyncTransactions = false;
        List<MutableTimer> mainThreadRootTimers = new ArrayList<>();
        MutableThreadStats mainThreadStats = new MutableThreadStats();
        MutableTimer auxThreadRootTimer = MutableTimer.createAuxThreadRootTimer();
        MutableThreadStats auxThreadStats = new MutableThreadStats();
        List<MutableTimer> asyncTimers = new ArrayList<>();
        for (Row row : rows) {
            int i = 0;
            totalDurationNanos += row.getDouble(i++);
            transactionCount += row.getLong(i++);
            if (row.getBool(i++)) {
                asyncTransactions = true;
            }
            List<Aggregate.Timer> toBeMergedMainThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            MutableAggregate.mergeRootTimers(toBeMergedMainThreadRootTimers, mainThreadRootTimers);
            mainThreadStats.addTotalCpuNanos(getNextThreadStat(row, i++));
            mainThreadStats.addTotalBlockedNanos(getNextThreadStat(row, i++));
            mainThreadStats.addTotalWaitedNanos(getNextThreadStat(row, i++));
            mainThreadStats.addTotalAllocatedBytes(getNextThreadStat(row, i++));
            // reading delimited singleton list for backwards compatibility with data written
            // prior to 0.12.0
            List<Aggregate.Timer> list =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            Aggregate.Timer toBeMergedAuxThreadRootTimer = list.isEmpty() ? null : list.get(0);
            if (toBeMergedAuxThreadRootTimer == null) {
                i += 4;
            } else {
                auxThreadRootTimer.merge(toBeMergedAuxThreadRootTimer);
                auxThreadStats.addTotalCpuNanos(getNextThreadStat(row, i++));
                auxThreadStats.addTotalBlockedNanos(getNextThreadStat(row, i++));
                auxThreadStats.addTotalWaitedNanos(getNextThreadStat(row, i++));
                auxThreadStats.addTotalAllocatedBytes(getNextThreadStat(row, i++));
            }
            List<Aggregate.Timer> toBeMergedAsyncTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            MutableAggregate.mergeRootTimers(toBeMergedAsyncTimers, asyncTimers);
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(overviewTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(overviewTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
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
        boundStatement.setDouble(i++, mainThreadStats.getTotalCpuNanos());
        boundStatement.setDouble(i++, mainThreadStats.getTotalBlockedNanos());
        boundStatement.setDouble(i++, mainThreadStats.getTotalWaitedNanos());
        boundStatement.setDouble(i++, mainThreadStats.getTotalAllocatedBytes());
        if (auxThreadRootTimer.getCount() == 0) {
            boundStatement.setToNull(i++);
            boundStatement.setToNull(i++);
            boundStatement.setToNull(i++);
            boundStatement.setToNull(i++);
            boundStatement.setToNull(i++);
        } else {
            // writing as delimited singleton list for backwards compatibility with data written
            // prior to 0.12.0
            boundStatement.setBytes(i++, Messages
                    .toByteBuffer(MutableAggregate.toProto(ImmutableList.of(auxThreadRootTimer))));
            boundStatement.setDouble(i++, auxThreadStats.getTotalCpuNanos());
            boundStatement.setDouble(i++, auxThreadStats.getTotalBlockedNanos());
            boundStatement.setDouble(i++, auxThreadStats.getTotalWaitedNanos());
            boundStatement.setDouble(i++, auxThreadStats.getTotalAllocatedBytes());
        }
        if (asyncTimers.isEmpty()) {
            boundStatement.setToNull(i++);
        } else {
            boundStatement.setBytes(i++,
                    Messages.toByteBuffer(MutableAggregate.toProto(asyncTimers)));
        }
        boundStatement.setInt(i++, rollup.adjustedTTL().generalTTL());
        return session.writeAsync(boundStatement);
    }

    private ListenableFuture<?> rollupHistogram(RollupParams rollup, AggregateQuery query,
            ScratchBuffer scratchBuffer) throws Exception {
        ListenableFuture<ResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, histogramTable, true);
        return MoreFutures.rollupAsync(future, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupHistogramFromRows(rollup, query, rows, scratchBuffer);
            }
        });
    }

    private ListenableFuture<?> rollupHistogramFromChildren(RollupParams rollup,
            AggregateQuery query, Collection<String> childAgentRollupIds,
            ScratchBuffer scratchBuffer) throws Exception {
        List<ListenableFuture<ResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, histogramTable, true);
        return MoreFutures.rollupAsync(futures, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupHistogramFromRows(rollup, query, rows, scratchBuffer);
            }
        });
    }

    private ListenableFuture<?> rollupHistogramFromRows(RollupParams rollup, AggregateQuery query,
            Iterable<Row> rows, ScratchBuffer scratchBuffer) throws Exception {
        double totalDurationNanos = 0;
        long transactionCount = 0;
        LazyHistogram durationNanosHistogram = new LazyHistogram();
        for (Row row : rows) {
            int i = 0;
            totalDurationNanos += row.getDouble(i++);
            transactionCount += row.getLong(i++);
            ByteBuffer bytes = checkNotNull(row.getBytes(i++));
            durationNanosHistogram.merge(Aggregate.Histogram.parseFrom(bytes));
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(histogramTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(histogramTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, transactionCount);
        boundStatement.setBytes(i++, toByteBuffer(durationNanosHistogram.toProto(scratchBuffer)));
        boundStatement.setInt(i++, rollup.adjustedTTL().generalTTL());
        return session.writeAsync(boundStatement);
    }

    private ListenableFuture<?> rollupThroughput(RollupParams rollup, AggregateQuery query)
            throws Exception {
        ListenableFuture<ResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, throughputTable, true);
        return MoreFutures.rollupAsync(future, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupThroughputFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupThroughputFromChildren(RollupParams rollup,
            AggregateQuery query, Collection<String> childAgentRollupIds) throws Exception {
        List<ListenableFuture<ResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, throughputTable, true);
        return MoreFutures.rollupAsync(futures, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupThroughputFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupThroughputFromRows(RollupParams rollup, AggregateQuery query,
            Iterable<Row> rows) throws Exception {
        long transactionCount = 0;
        // error_count is null for data inserted prior to glowroot central 0.9.18
        // rolling up any interval with null error_count should result in null error_count
        boolean hasMissingErrorCount = false;
        long errorCount = 0;
        for (Row row : rows) {
            transactionCount += row.getLong(0);
            if (row.isNull(1)) {
                hasMissingErrorCount = true;
            } else {
                errorCount += row.getLong(1);
            }
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(throughputTable, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(throughputTable, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setLong(i++, transactionCount);
        if (hasMissingErrorCount) {
            boundStatement.setToNull(i++);
        } else {
            boundStatement.setLong(i++, errorCount);
        }
        boundStatement.setInt(i++, rollup.adjustedTTL().generalTTL());
        return session.writeAsync(boundStatement);
    }

    private ListenableFuture<?> rollupQueries(RollupParams rollup, AggregateQuery query)
            throws Exception {
        ListenableFuture<ResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, queryTable, false);
        return MoreFutures.rollupAsync(future, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupQueriesFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupQueriesFromChildren(RollupParams rollup,
            AggregateQuery query, Collection<String> childAgentRollupIds) throws Exception {
        List<ListenableFuture<ResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, queryTable, false);
        return MoreFutures.rollupAsync(futures, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupQueriesFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupQueriesFromRows(RollupParams rollup, AggregateQuery query,
            Iterable<Row> rows) throws Exception {
        QueryCollector collector =
                new QueryCollector(rollup.maxQueryAggregatesPerTransactionAggregate());
        for (Row row : rows) {
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
        return insertQueries(collector.getSortedAndTruncatedQueries(), rollup.rollupLevel(),
                rollup.agentRollupId(), query.transactionType(), query.transactionName(),
                query.to(), rollup.adjustedTTL());
    }

    private ListenableFuture<?> rollupServiceCalls(RollupParams rollup, AggregateQuery query)
            throws Exception {
        ListenableFuture<ResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, serviceCallTable, false);
        return MoreFutures.rollupAsync(future, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupServiceCallsFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupServiceCallsFromChildren(RollupParams rollup,
            AggregateQuery query, Collection<String> childAgentRollupIds) throws Exception {
        List<ListenableFuture<ResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, serviceCallTable, false);
        return MoreFutures.rollupAsync(futures, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupServiceCallsFromRows(rollup, query, rows);
            }
        });
    }

    private ListenableFuture<?> rollupServiceCallsFromRows(RollupParams rollup,
            AggregateQuery query, Iterable<Row> rows) throws Exception {
        ServiceCallCollector collector =
                new ServiceCallCollector(rollup.maxServiceCallAggregatesPerTransactionAggregate());
        for (Row row : rows) {
            int i = 0;
            String serviceCallType = checkNotNull(row.getString(i++));
            String serviceCallText = checkNotNull(row.getString(i++));
            double totalDurationNanos = row.getDouble(i++);
            long executionCount = row.getLong(i++);
            collector.mergeServiceCall(serviceCallType, serviceCallText, totalDurationNanos,
                    executionCount);
        }
        return insertServiceCalls(collector.getSortedAndTruncatedServiceCalls(),
                rollup.rollupLevel(), rollup.agentRollupId(), query.transactionType(),
                query.transactionName(), query.to(), rollup.adjustedTTL());
    }

    private ListenableFuture<?> rollupThreadProfile(RollupParams rollup, AggregateQuery query,
            Table table) throws Exception {
        ListenableFuture<ResultSet> future =
                executeQueryForRollup(rollup.agentRollupId(), query, table, false);
        return MoreFutures.rollupAsync(future, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupThreadProfileFromRows(rollup, query, rows, table);
            }
        });
    }

    private ListenableFuture<?> rollupThreadProfileFromChildren(RollupParams rollup,
            AggregateQuery query, Collection<String> childAgentRollupIds, Table table)
            throws Exception {
        List<ListenableFuture<ResultSet>> futures =
                getRowsForRollupFromChildren(query, childAgentRollupIds, table, false);
        return MoreFutures.rollupAsync(futures, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupThreadProfileFromRows(rollup, query, rows, table);
            }
        });
    }

    private ListenableFuture<?> rollupThreadProfileFromRows(RollupParams rollup,
            AggregateQuery query, Iterable<Row> rows, Table table) throws Exception {
        MutableProfile profile = new MutableProfile();
        for (Row row : rows) {
            ByteBuffer bytes = checkNotNull(row.getBytes(0));
            profile.merge(Profile.parseFrom(bytes));
        }
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = getInsertOverallPS(table, rollup.rollupLevel()).bind();
        } else {
            boundStatement = getInsertTransactionPS(table, rollup.rollupLevel()).bind();
        }
        int i = 0;
        boundStatement.setString(i++, rollup.agentRollupId());
        boundStatement.setString(i++, query.transactionType());
        if (query.transactionName() != null) {
            boundStatement.setString(i++, query.transactionName());
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
        boundStatement.setBytes(i++, toByteBuffer(profile.toProto()));
        boundStatement.setInt(i++, rollup.adjustedTTL().profileTTL());
        return session.writeAsync(boundStatement);
    }

    private Map<String, ListenableFuture<ResultSet>> getRowsForSummaryRollupFromChildren(
            AggregateQuery query, Collection<String> childAgentRollupIds, Table table,
            boolean warnIfNoResults) throws Exception {
        Map<String, ListenableFuture<ResultSet>> futures = new HashMap<>();
        for (String childAgentRollupId : childAgentRollupIds) {
            BoundStatement boundStatement =
                    checkNotNull(readTransactionForRollupFromChildPS.get(table)).bind();
            bindQueryForRollupFromChild(boundStatement, childAgentRollupId, query);
            if (warnIfNoResults) {
                futures.put(childAgentRollupId, session.readAsyncWarnIfNoRows(boundStatement,
                        "no summary table records found for agentRollupId={}, query={}",
                        childAgentRollupId, query));
            } else {
                futures.put(childAgentRollupId, session.readAsync(boundStatement));
            }
        }
        return futures;
    }

    private List<ListenableFuture<ResultSet>> getRowsForRollupFromChildren(AggregateQuery query,
            Collection<String> childAgentRollupIds, Table table, boolean warnIfNoResults)
            throws Exception {
        List<ListenableFuture<ResultSet>> futures = new ArrayList<>();
        for (String childAgentRollupId : childAgentRollupIds) {
            futures.add(executeQueryForRollupFromChild(childAgentRollupId, query, table,
                    warnIfNoResults));
        }
        return futures;
    }

    private List<ListenableFuture<?>> storeOverallAggregate(String agentRollupId,
            String transactionType, long captureTime, Aggregate aggregate,
            List<Aggregate.SharedQueryText> sharedQueryTexts, TTL adjustedTTL) throws Exception {

        final int rollupLevel = 0;

        List<ListenableFuture<?>> futures = new ArrayList<>();
        BoundStatement boundStatement = getInsertOverallPS(summaryTable, rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, adjustedTTL.generalTTL());
        futures.add(session.writeAsync(boundStatement));

        if (aggregate.getErrorCount() > 0) {
            boundStatement = getInsertOverallPS(errorSummaryTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setLong(i++, aggregate.getErrorCount());
            boundStatement.setLong(i++, aggregate.getTransactionCount());
            boundStatement.setInt(i++, adjustedTTL.generalTTL());
            futures.add(session.writeAsync(boundStatement));
        }

        boundStatement = getInsertOverallPS(overviewTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, i++, adjustedTTL);
        futures.add(session.writeAsync(boundStatement));

        boundStatement = getInsertOverallPS(histogramTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setBytes(i++, toByteBuffer(aggregate.getDurationNanosHistogram()));
        boundStatement.setInt(i++, adjustedTTL.generalTTL());
        futures.add(session.writeAsync(boundStatement));

        boundStatement = getInsertOverallPS(throughputTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setLong(i++, aggregate.getErrorCount());
        boundStatement.setInt(i++, adjustedTTL.generalTTL());
        futures.add(session.writeAsync(boundStatement));

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            boundStatement = getInsertOverallPS(mainThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, adjustedTTL.profileTTL());
            futures.add(session.writeAsync(boundStatement));
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            boundStatement = getInsertOverallPS(auxThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, adjustedTTL.profileTTL());
            futures.add(session.writeAsync(boundStatement));
        }
        futures.addAll(insertQueries(getQueries(aggregate), sharedQueryTexts, rollupLevel,
                agentRollupId, transactionType, null, captureTime, adjustedTTL));
        futures.addAll(insertServiceCallsProto(getServiceCalls(aggregate), rollupLevel,
                agentRollupId, transactionType, null, captureTime, adjustedTTL));
        return futures;
    }

    private List<Future<?>> storeTransactionAggregate(String agentRollupId, String transactionType,
            String transactionName, long captureTime, Aggregate aggregate,
            List<Aggregate.SharedQueryText> sharedQueryTexts, TTL adjustedTTL) throws Exception {

        final int rollupLevel = 0;

        List<Future<?>> futures = new ArrayList<>();
        BoundStatement boundStatement = getInsertTransactionPS(overviewTable, rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, transactionName);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, i++, adjustedTTL);
        futures.add(session.writeAsync(boundStatement));

        boundStatement = getInsertTransactionPS(histogramTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, transactionName);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setBytes(i++, toByteBuffer(aggregate.getDurationNanosHistogram()));
        boundStatement.setInt(i++, adjustedTTL.generalTTL());
        futures.add(session.writeAsync(boundStatement));

        boundStatement = getInsertTransactionPS(throughputTable, rollupLevel).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, transactionName);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setLong(i++, aggregate.getErrorCount());
        boundStatement.setInt(i++, adjustedTTL.generalTTL());
        futures.add(session.writeAsync(boundStatement));

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            boundStatement = getInsertTransactionPS(mainThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setString(i++, transactionName);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, adjustedTTL.profileTTL());
            futures.add(session.writeAsync(boundStatement));
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            boundStatement = getInsertTransactionPS(auxThreadProfileTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setString(i++, transactionName);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setBytes(i++, toByteBuffer(profile));
            boundStatement.setInt(i++, adjustedTTL.profileTTL());
            futures.add(session.writeAsync(boundStatement));
        }
        futures.addAll(insertQueries(getQueries(aggregate), sharedQueryTexts, rollupLevel,
                agentRollupId, transactionType, transactionName, captureTime, adjustedTTL));
        futures.addAll(insertServiceCallsProto(getServiceCalls(aggregate), rollupLevel,
                agentRollupId, transactionType, transactionName, captureTime, adjustedTTL));
        return futures;
    }

    private List<Future<?>> storeTransactionNameSummary(String agentRollupId,
            String transactionType, String transactionName, long captureTime, Aggregate aggregate,
            TTL adjustedTTL) throws Exception {

        final int rollupLevel = 0;

        List<Future<?>> futures = new ArrayList<>();
        BoundStatement boundStatement = getInsertTransactionPS(summaryTable, rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setString(i++, transactionName);
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setInt(i++, adjustedTTL.generalTTL());
        futures.add(session.writeAsync(boundStatement));

        if (aggregate.getErrorCount() > 0) {
            boundStatement = getInsertTransactionPS(errorSummaryTable, rollupLevel).bind();
            i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setString(i++, transactionName);
            boundStatement.setLong(i++, aggregate.getErrorCount());
            boundStatement.setLong(i++, aggregate.getTransactionCount());
            boundStatement.setInt(i++, adjustedTTL.generalTTL());
            futures.add(session.writeAsync(boundStatement));
        }
        return futures;
    }

    private List<ListenableFuture<?>> insertQueries(List<Aggregate.Query> queries,
            List<Aggregate.SharedQueryText> sharedQueryTexts, int rollupLevel, String agentRollupId,
            String transactionType, @Nullable String transactionName, long captureTime,
            TTL adjustedTTL) throws Exception {
        List<ListenableFuture<?>> futures = new ArrayList<>();
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
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            if (transactionName != null) {
                boundStatement.setString(i++, transactionName);
            }
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setString(i++, query.getType());
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
            boundStatement.setInt(i++, adjustedTTL.queryTTL());
            futures.add(session.writeAsync(boundStatement));
        }
        return futures;
    }

    private ListenableFuture<?> insertQueries(List<MutableQuery> queries, int rollupLevel,
            String agentRollupId, String transactionType, @Nullable String transactionName,
            long captureTime, TTL adjustedTTL) throws Exception {
        List<ListenableFuture<?>> futures = new ArrayList<>();
        for (MutableQuery query : queries) {
            BoundStatement boundStatement;
            if (transactionName == null) {
                boundStatement = getInsertOverallPS(queryTable, rollupLevel).bind();
            } else {
                boundStatement = getInsertTransactionPS(queryTable, rollupLevel).bind();
            }
            String fullTextSha1 = query.getFullTextSha1();
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            if (transactionName != null) {
                boundStatement.setString(i++, transactionName);
            }
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setString(i++, query.getType());
            boundStatement.setString(i++, query.getTruncatedText());
            // full_query_text_sha1 cannot be null since it is used in clustering key
            boundStatement.setString(i++, Strings.nullToEmpty(fullTextSha1));
            boundStatement.setDouble(i++, query.getTotalDurationNanos());
            boundStatement.setLong(i++, query.getExecutionCount());
            if (query.hasTotalRows()) {
                boundStatement.setLong(i++, query.getTotalRows());
            } else {
                boundStatement.setToNull(i++);
            }
            boundStatement.setInt(i++, adjustedTTL.queryTTL());
            futures.add(session.writeAsync(boundStatement));
        }
        return Futures.allAsList(futures);
    }

    private List<ListenableFuture<?>> insertServiceCallsProto(
            List<Aggregate.ServiceCall> serviceCalls, int rollupLevel, String agentRollupId,
            String transactionType, @Nullable String transactionName, long captureTime,
            TTL adjustedTTL) throws Exception {
        List<ListenableFuture<?>> futures = new ArrayList<>();
        for (Aggregate.ServiceCall serviceCall : serviceCalls) {
            BoundStatement boundStatement;
            if (transactionName == null) {
                boundStatement = getInsertOverallPS(serviceCallTable, rollupLevel).bind();
            } else {
                boundStatement = getInsertTransactionPS(serviceCallTable, rollupLevel).bind();
            }
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            if (transactionName != null) {
                boundStatement.setString(i++, transactionName);
            }
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setString(i++, serviceCall.getType());
            boundStatement.setString(i++, serviceCall.getText());
            boundStatement.setDouble(i++, serviceCall.getTotalDurationNanos());
            boundStatement.setLong(i++, serviceCall.getExecutionCount());
            boundStatement.setInt(i++, adjustedTTL.serviceCallTTL());
            futures.add(session.writeAsync(boundStatement));
        }
        return futures;
    }

    private ListenableFuture<?> insertServiceCalls(List<MutableServiceCall> serviceCalls,
            int rollupLevel, String agentRollupId, String transactionType,
            @Nullable String transactionName, long captureTime, TTL adjustedTTL) throws Exception {
        List<ListenableFuture<?>> futures = new ArrayList<>();
        for (MutableServiceCall serviceCall : serviceCalls) {
            BoundStatement boundStatement;
            if (transactionName == null) {
                boundStatement = getInsertOverallPS(serviceCallTable, rollupLevel).bind();
            } else {
                boundStatement = getInsertTransactionPS(serviceCallTable, rollupLevel).bind();
            }
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, transactionType);
            if (transactionName != null) {
                boundStatement.setString(i++, transactionName);
            }
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setString(i++, serviceCall.getType());
            boundStatement.setString(i++, serviceCall.getText());
            boundStatement.setDouble(i++, serviceCall.getTotalDurationNanos());
            boundStatement.setLong(i++, serviceCall.getExecutionCount());
            boundStatement.setInt(i++, adjustedTTL.serviceCallTTL());
            futures.add(session.writeAsync(boundStatement));
        }
        return Futures.allAsList(futures);
    }

    private PreparedStatement getInsertOverallPS(Table table, int rollupLevel) {
        return checkNotNull(insertOverallPS.get(table)).get(rollupLevel);
    }

    private PreparedStatement getInsertTransactionPS(Table table, int rollupLevel) {
        return checkNotNull(insertTransactionPS.get(table)).get(rollupLevel);
    }

    private ResultSet executeQuery(String agentRollupId, SummaryQuery query, Table table)
            throws Exception {
        BoundStatement boundStatement =
                checkNotNull(readOverallPS.get(table)).get(query.rollupLevel()).bind();
        bindQuery(boundStatement, agentRollupId, query);
        return session.read(boundStatement);
    }

    private ResultSet executeQuery(String agentRollupId, AggregateQuery query, Table table)
            throws Exception {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = checkNotNull(readOverallPS.get(table)).get(query.rollupLevel()).bind();
        } else {
            boundStatement =
                    checkNotNull(readTransactionPS.get(table)).get(query.rollupLevel()).bind();
        }
        bindQuery(boundStatement, agentRollupId, query);
        return session.read(boundStatement);
    }

    private ListenableFuture<ResultSet> executeQueryForRollup(String agentRollupId,
            AggregateQuery query, Table table, boolean warnIfNoResults) throws Exception {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement =
                    checkNotNull(readOverallForRollupPS.get(table)).get(query.rollupLevel()).bind();
        } else {
            boundStatement = checkNotNull(readTransactionForRollupPS.get(table))
                    .get(query.rollupLevel()).bind();
        }
        bindQuery(boundStatement, agentRollupId, query);
        if (warnIfNoResults) {
            return session.readAsyncWarnIfNoRows(boundStatement,
                    "no {} records found for agentRollupId={}, query={}", table.partialName(),
                    agentRollupId, query);
        } else {
            return session.readAsync(boundStatement);
        }
    }

    private ListenableFuture<ResultSet> executeQueryForRollupFromChild(String childAgentRollupId,
            AggregateQuery query, Table table, boolean warnIfNoResults) throws Exception {
        BoundStatement boundStatement;
        if (query.transactionName() == null) {
            boundStatement = checkNotNull(readOverallForRollupFromChildPS.get(table)).bind();
        } else {
            boundStatement = checkNotNull(readTransactionForRollupFromChildPS.get(table)).bind();
        }
        bindQueryForRollupFromChild(boundStatement, childAgentRollupId, query);
        if (warnIfNoResults) {
            return session.readAsyncWarnIfNoRows(boundStatement,
                    "no {} records found for agentRollupId={}, query={}", table.partialName(),
                    childAgentRollupId, query);
        } else {
            return session.readAsync(boundStatement);
        }
    }

    private void mergeProfilesInto(String agentRollupId, AggregateQuery query, Table profileTable,
            ProfileCollector collector) throws Exception {
        ResultSet results = executeQuery(agentRollupId, query, profileTable);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(0)).getTime());
            ByteBuffer bytes = checkNotNull(row.getBytes(1));
            // TODO optimize this byte copying
            Profile profile = Profile.parseFrom(bytes);
            collector.mergeProfile(profile);
            collector.updateLastCaptureTime(captureTime);
        }
    }

    private List<TTL> getTTLs() throws Exception {
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

    private RollupParams getRollupParams(String agentRollupId, String agentRollupIdForMeta,
            int rollupLevel, TTL adjustedTTL) throws Exception {
        return ImmutableRollupParams.builder()
                .agentRollupId(agentRollupId)
                .rollupLevel(rollupLevel)
                .adjustedTTL(adjustedTTL)
                .maxQueryAggregatesPerTransactionAggregate(
                        getMaxQueryAggregatesPerTransactionAggregate(agentRollupIdForMeta))
                .maxServiceCallAggregatesPerTransactionAggregate(
                        getMaxServiceCallAggregatesPerTransactionAggregate(agentRollupIdForMeta))
                .build();
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
        summary.transactionCount += row.getLong(i++);
        return transactionName;
    }

    private static void bindAggregate(BoundStatement boundStatement, Aggregate aggregate,
            int startIndex, TTL adjustedTTL) throws IOException {
        int i = startIndex;
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        boundStatement.setBool(i++, aggregate.getAsyncTransactions());
        boundStatement.setBytes(i++, Messages.toByteBuffer(aggregate.getMainThreadRootTimerList()));
        if (aggregate.hasOldMainThreadStats()) {
            // data from agent prior to 0.10.9
            Aggregate.OldThreadStats mainThreadStats = aggregate.getOldMainThreadStats();
            boundStatement.setDouble(i++, mainThreadStats.getTotalCpuNanos().getValue());
            boundStatement.setDouble(i++, mainThreadStats.getTotalBlockedNanos().getValue());
            boundStatement.setDouble(i++, mainThreadStats.getTotalWaitedNanos().getValue());
            boundStatement.setDouble(i++, mainThreadStats.getTotalAllocatedBytes().getValue());
        } else {
            Aggregate.ThreadStats mainThreadStats = aggregate.getMainThreadStats();
            boundStatement.setDouble(i++, mainThreadStats.getTotalCpuNanos());
            boundStatement.setDouble(i++, mainThreadStats.getTotalBlockedNanos());
            boundStatement.setDouble(i++, mainThreadStats.getTotalWaitedNanos());
            boundStatement.setDouble(i++, mainThreadStats.getTotalAllocatedBytes());
        }
        if (aggregate.hasAuxThreadRootTimer()) {
            // writing as delimited singleton list for backwards compatibility with data written
            // prior to 0.12.0
            boundStatement.setBytes(i++,
                    Messages.toByteBuffer(ImmutableList.of(aggregate.getAuxThreadRootTimer())));
            if (aggregate.hasOldAuxThreadStats()) {
                Aggregate.OldThreadStats auxThreadStats = aggregate.getOldAuxThreadStats();
                boundStatement.setDouble(i++, auxThreadStats.getTotalCpuNanos().getValue());
                boundStatement.setDouble(i++, auxThreadStats.getTotalBlockedNanos().getValue());
                boundStatement.setDouble(i++, auxThreadStats.getTotalWaitedNanos().getValue());
                boundStatement.setDouble(i++, auxThreadStats.getTotalAllocatedBytes().getValue());
            } else {
                Aggregate.ThreadStats auxThreadStats = aggregate.getAuxThreadStats();
                boundStatement.setDouble(i++, auxThreadStats.getTotalCpuNanos());
                boundStatement.setDouble(i++, auxThreadStats.getTotalBlockedNanos());
                boundStatement.setDouble(i++, auxThreadStats.getTotalWaitedNanos());
                boundStatement.setDouble(i++, auxThreadStats.getTotalAllocatedBytes());
            }
        } else {
            boundStatement.setToNull(i++);
            boundStatement.setToNull(i++);
            boundStatement.setToNull(i++);
            boundStatement.setToNull(i++);
            boundStatement.setToNull(i++);
        }
        List<Aggregate.Timer> asyncTimers = aggregate.getAsyncTimerList();
        if (asyncTimers.isEmpty()) {
            boundStatement.setToNull(i++);
        } else {
            boundStatement.setBytes(i++, Messages.toByteBuffer(asyncTimers));
        }
        boundStatement.setInt(i++, adjustedTTL.generalTTL());
    }

    private static void bindQuery(BoundStatement boundStatement, String agentRollupId,
            SummaryQuery query) {
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.from()));
        boundStatement.setTimestamp(i++, new Date(query.to()));
    }

    private static void bindQuery(BoundStatement boundStatement, String agentRollupId,
            AggregateQuery query) {
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            boundStatement.setString(i++, transactionName);
        }
        boundStatement.setTimestamp(i++, new Date(query.from()));
        boundStatement.setTimestamp(i++, new Date(query.to()));
    }

    private static void bindQueryForRollupFromChild(BoundStatement boundStatement,
            String agentRollupId, AggregateQuery query) {
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            boundStatement.setString(i++, transactionName);
        }
        boundStatement.setTimestamp(i++, new Date(query.to()));
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

    private int getMaxQueryAggregatesPerTransactionAggregate(String agentRollupId)
            throws Exception {
        AdvancedConfig advancedConfig;
        try {
            advancedConfig = configRepository.getAdvancedConfig(agentRollupId);
        } catch (AgentConfigNotFoundException e) {
            return ConfigDefaults.ADVANCED_MAX_QUERY_AGGREGATES;
        }
        if (advancedConfig.hasMaxQueryAggregates()) {
            return advancedConfig.getMaxQueryAggregates().getValue();
        } else {
            return ConfigDefaults.ADVANCED_MAX_QUERY_AGGREGATES;
        }
    }

    private int getMaxServiceCallAggregatesPerTransactionAggregate(String agentRollupId)
            throws Exception {
        AdvancedConfig advancedConfig;
        try {
            advancedConfig = configRepository.getAdvancedConfig(agentRollupId);
        } catch (AgentConfigNotFoundException e) {
            return ConfigDefaults.ADVANCED_MAX_SERVICE_CALL_AGGREGATES;
        }
        if (advancedConfig.hasMaxServiceCallAggregates()) {
            return advancedConfig.getMaxServiceCallAggregates().getValue();
        } else {
            return ConfigDefaults.ADVANCED_MAX_SERVICE_CALL_AGGREGATES;
        }
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
    }

    private static class MutableErrorSummary {
        private long errorCount;
        private long transactionCount;
    }

}
