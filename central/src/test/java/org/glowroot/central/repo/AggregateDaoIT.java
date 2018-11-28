/*
 * Copyright 2016-2018 the original author or authors.
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PoolingOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.central.v09support.AggregateDaoWithV09Support;
import org.glowroot.common.ConfigDefaults;
import org.glowroot.common.live.ImmutableAggregateQuery;
import org.glowroot.common.live.ImmutableSummaryQuery;
import org.glowroot.common.live.LiveAggregateRepository.AggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.SummaryQuery;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallErrorSummaryCollector.OverallErrorSummary;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector.OverallSummary;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.Result;
import org.glowroot.common.model.TransactionNameErrorSummaryCollector;
import org.glowroot.common.model.TransactionNameErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionNameErrorSummaryCollector.TransactionNameErrorSummary;
import org.glowroot.common.model.TransactionNameSummaryCollector;
import org.glowroot.common.model.TransactionNameSummaryCollector.SummarySortOrder;
import org.glowroot.common.model.TransactionNameSummaryCollector.TransactionNameSummary;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.util.RollupLevelService;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OldTransactionAggregate;
import org.glowroot.wire.api.model.Proto.OptionalInt32;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

import static org.assertj.core.api.Assertions.assertThat;

public class AggregateDaoIT {

    private static final AdvancedConfig DEFAULT_ADVANCED_CONFIG = AdvancedConfig.newBuilder()
            .setMaxQueryAggregates(OptionalInt32.newBuilder()
                    .setValue(ConfigDefaults.ADVANCED_MAX_QUERY_AGGREGATES))
            .setMaxServiceCallAggregates(OptionalInt32.newBuilder()
                    .setValue(ConfigDefaults.ADVANCED_MAX_SERVICE_CALL_AGGREGATES))
            .build();

    private static ClusterManager clusterManager;
    private static Cluster cluster;
    private static Session session;
    private static AgentConfigDao agentConfigDao;
    private static ActiveAgentDao activeAgentDao;
    private static ExecutorService asyncExecutor;
    private static AggregateDao aggregateDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        clusterManager = ClusterManager.create();
        cluster = Clusters.newCluster();
        session = new Session(cluster.newSession(), "glowroot_unit_tests", null,
                PoolingOptions.DEFAULT_MAX_QUEUE_SIZE);
        CentralConfigDao centralConfigDao = new CentralConfigDao(session, clusterManager);
        agentConfigDao = new AgentConfigDao(session, clusterManager);
        UserDao userDao = new UserDao(session, clusterManager);
        RoleDao roleDao = new RoleDao(session, clusterManager);
        ConfigRepositoryImpl configRepository =
                new ConfigRepositoryImpl(centralConfigDao, agentConfigDao, userDao, roleDao, "");
        TransactionTypeDao transactionTypeDao =
                new TransactionTypeDao(session, configRepository, clusterManager);
        asyncExecutor = Executors.newCachedThreadPool();
        FullQueryTextDao fullQueryTextDao =
                new FullQueryTextDao(session, configRepository, asyncExecutor);
        RollupLevelService rollupLevelService =
                new RollupLevelService(configRepository, Clock.systemClock());
        activeAgentDao = new ActiveAgentDao(session, agentConfigDao, configRepository,
                rollupLevelService, Clock.systemClock());
        aggregateDao = new AggregateDaoWithV09Support(ImmutableSet.of(), 0, 0, Clock.systemClock(),
                new AggregateDaoImpl(session, activeAgentDao, transactionTypeDao, fullQueryTextDao,
                        configRepository, asyncExecutor, Clock.systemClock()));
    }

    @AfterClass
    public static void tearDown() throws Exception {
        asyncExecutor.shutdown();
        session.close();
        cluster.close();
        clusterManager.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Before
    public void before() throws Exception {
        session.updateSchemaWithRetry("truncate agent_config");
    }

    @Test
    public void shouldRollup() throws Exception {

        agentConfigDao.store("one", AgentConfig.newBuilder()
                .setAdvancedConfig(DEFAULT_ADVANCED_CONFIG)
                .build(), true);

        aggregateDao.truncateAll();
        List<Aggregate.SharedQueryText> sharedQueryText = ImmutableList
                .of(Aggregate.SharedQueryText.newBuilder().setFullText("select 1").build());
        aggregateDao.store("one", 60000, createData(), sharedQueryText);
        aggregateDao.store("one", 120000, createData(), sharedQueryText);
        aggregateDao.store("one", 360000, createData(), sharedQueryText);

        // check non-rolled up data
        SummaryQuery summaryQuery = ImmutableSummaryQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();
        AggregateQuery aggregateQuery = ImmutableAggregateQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();

        OverallSummaryCollector overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("one", summaryQuery, overallSummaryCollector);
        OverallSummary overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        TransactionNameSummaryCollector transactionNameSummaryCollector =
                new TransactionNameSummaryCollector();
        SummarySortOrder sortOrder = SummarySortOrder.TOTAL_TIME;
        aggregateDao.mergeTransactionNameSummariesInto("one", summaryQuery, sortOrder, 10,
                transactionNameSummaryCollector);
        Result<TransactionNameSummary> result =
                transactionNameSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        OverallErrorSummaryCollector overallErrorSummaryCollector =
                new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("one", summaryQuery,
                overallErrorSummaryCollector);
        OverallErrorSummary overallErrorSummary =
                overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        TransactionNameErrorSummaryCollector errorSummaryCollector =
                new TransactionNameErrorSummaryCollector();
        ErrorSummarySortOrder errorSortOrder = ErrorSummarySortOrder.ERROR_COUNT;
        aggregateDao.mergeTransactionNameErrorSummariesInto("one", summaryQuery, errorSortOrder, 10,
                errorSummaryCollector);
        Result<TransactionNameErrorSummary> errorSummaryResult =
                errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        List<OverviewAggregate> overviewAggregates =
                aggregateDao.readOverviewAggregates("one", aggregateQuery);
        assertThat(overviewAggregates).hasSize(2);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(overviewAggregates.get(1).transactionCount()).isEqualTo(3);

        List<PercentileAggregate> percentileAggregates =
                aggregateDao.readPercentileAggregates("one", aggregateQuery);
        assertThat(percentileAggregates).hasSize(2);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(percentileAggregates.get(1).transactionCount()).isEqualTo(3);

        List<ThroughputAggregate> throughputAggregates =
                aggregateDao.readThroughputAggregates("one", aggregateQuery);
        assertThat(throughputAggregates).hasSize(2);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(throughputAggregates.get(1).transactionCount()).isEqualTo(3);

        QueryCollector queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("one", aggregateQuery, queryCollector);
        List<MutableQuery> queries = queryCollector.getSortedAndTruncatedQueries();
        assertThat(queries).hasSize(1);
        MutableQuery query = queries.get(0);
        assertThat(query.getType()).isEqualTo("sqlo");
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);

        // rollup
        aggregateDao.rollup("one");

        // check rolled-up data after rollup
        summaryQuery = ImmutableSummaryQuery.builder()
                .copyFrom(summaryQuery)
                .rollupLevel(1)
                .build();
        aggregateQuery = ImmutableAggregateQuery.builder()
                .copyFrom(aggregateQuery)
                .rollupLevel(1)
                .build();

        overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("one", summaryQuery, overallSummaryCollector);
        overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        transactionNameSummaryCollector = new TransactionNameSummaryCollector();
        aggregateDao.mergeTransactionNameSummariesInto("one", summaryQuery, sortOrder, 10,
                transactionNameSummaryCollector);
        result = transactionNameSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        overallErrorSummaryCollector = new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("one", summaryQuery,
                overallErrorSummaryCollector);
        overallErrorSummary = overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        errorSummaryCollector = new TransactionNameErrorSummaryCollector();
        aggregateDao.mergeTransactionNameErrorSummariesInto("one", summaryQuery, errorSortOrder, 10,
                errorSummaryCollector);
        errorSummaryResult = errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        overviewAggregates = aggregateDao.readOverviewAggregates("one", aggregateQuery);
        assertThat(overviewAggregates).hasSize(1);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(6);

        percentileAggregates = aggregateDao.readPercentileAggregates("one", aggregateQuery);
        assertThat(percentileAggregates).hasSize(1);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(6);

        throughputAggregates = aggregateDao.readThroughputAggregates("one", aggregateQuery);
        assertThat(throughputAggregates).hasSize(1);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(6);

        queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("one", aggregateQuery, queryCollector);
        queries = queryCollector.getSortedAndTruncatedQueries();
        assertThat(queries).hasSize(1);
        query = queries.get(0);
        assertThat(query.getType()).isEqualTo("sqlo");
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);
    }

    @Test
    public void shouldRollupFromChildren() throws Exception {

        agentConfigDao.store("the parent::one", AgentConfig.newBuilder()
                .setAdvancedConfig(DEFAULT_ADVANCED_CONFIG)
                .build(), true);

        aggregateDao.truncateAll();
        List<Aggregate.SharedQueryText> sharedQueryText = ImmutableList
                .of(Aggregate.SharedQueryText.newBuilder().setFullText("select 1").build());
        aggregateDao.store("the parent::one", 60000, createData(), sharedQueryText);
        aggregateDao.store("the parent::one", 120000, createData(), sharedQueryText);
        aggregateDao.store("the parent::one", 360000, createData(), sharedQueryText);

        // rollup
        aggregateDao.rollup("the parent::");

        // check level-0 rolled up from children data
        SummaryQuery summaryQuery = ImmutableSummaryQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();
        AggregateQuery aggregateQuery = ImmutableAggregateQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();

        OverallSummaryCollector overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("the parent::", summaryQuery, overallSummaryCollector);
        OverallSummary overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        TransactionNameSummaryCollector transactionNameSummaryCollector =
                new TransactionNameSummaryCollector();
        SummarySortOrder sortOrder = SummarySortOrder.TOTAL_TIME;
        aggregateDao.mergeTransactionNameSummariesInto("the parent::", summaryQuery, sortOrder, 10,
                transactionNameSummaryCollector);
        Result<TransactionNameSummary> result =
                transactionNameSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        OverallErrorSummaryCollector overallErrorSummaryCollector =
                new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("the parent::", summaryQuery,
                overallErrorSummaryCollector);
        OverallErrorSummary overallErrorSummary =
                overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        TransactionNameErrorSummaryCollector errorSummaryCollector =
                new TransactionNameErrorSummaryCollector();
        ErrorSummarySortOrder errorSortOrder = ErrorSummarySortOrder.ERROR_COUNT;
        aggregateDao.mergeTransactionNameErrorSummariesInto("the parent::", summaryQuery,
                errorSortOrder,
                10, errorSummaryCollector);
        Result<TransactionNameErrorSummary> errorSummaryResult =
                errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        List<OverviewAggregate> overviewAggregates =
                aggregateDao.readOverviewAggregates("the parent::", aggregateQuery);
        assertThat(overviewAggregates).hasSize(2);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(overviewAggregates.get(1).transactionCount()).isEqualTo(3);

        List<PercentileAggregate> percentileAggregates =
                aggregateDao.readPercentileAggregates("the parent::", aggregateQuery);
        assertThat(percentileAggregates).hasSize(2);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(percentileAggregates.get(1).transactionCount()).isEqualTo(3);

        List<ThroughputAggregate> throughputAggregates =
                aggregateDao.readThroughputAggregates("the parent::", aggregateQuery);
        assertThat(throughputAggregates).hasSize(2);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(throughputAggregates.get(1).transactionCount()).isEqualTo(3);

        QueryCollector queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("the parent::", aggregateQuery, queryCollector);
        List<MutableQuery> queries = queryCollector.getSortedAndTruncatedQueries();
        assertThat(queries).hasSize(1);
        MutableQuery query = queries.get(0);
        assertThat(query.getType()).isEqualTo("sqlo");
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);

        // check rolled-up data after rollup
        summaryQuery = ImmutableSummaryQuery.builder()
                .copyFrom(summaryQuery)
                .rollupLevel(1)
                .build();
        aggregateQuery = ImmutableAggregateQuery.builder()
                .copyFrom(aggregateQuery)
                .rollupLevel(1)
                .build();

        overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("the parent::", summaryQuery, overallSummaryCollector);
        overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        transactionNameSummaryCollector = new TransactionNameSummaryCollector();
        aggregateDao.mergeTransactionNameSummariesInto("the parent::", summaryQuery, sortOrder, 10,
                transactionNameSummaryCollector);
        result = transactionNameSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        overallErrorSummaryCollector = new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("the parent::", summaryQuery,
                overallErrorSummaryCollector);
        overallErrorSummary = overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        errorSummaryCollector = new TransactionNameErrorSummaryCollector();
        aggregateDao.mergeTransactionNameErrorSummariesInto("the parent::", summaryQuery,
                errorSortOrder,
                10, errorSummaryCollector);
        errorSummaryResult = errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        overviewAggregates = aggregateDao.readOverviewAggregates("the parent::", aggregateQuery);
        assertThat(overviewAggregates).hasSize(1);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(6);

        percentileAggregates =
                aggregateDao.readPercentileAggregates("the parent::", aggregateQuery);
        assertThat(percentileAggregates).hasSize(1);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(6);

        throughputAggregates =
                aggregateDao.readThroughputAggregates("the parent::", aggregateQuery);
        assertThat(throughputAggregates).hasSize(1);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(6);

        queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("the parent::", aggregateQuery, queryCollector);
        queries = queryCollector.getSortedAndTruncatedQueries();
        assertThat(queries).hasSize(1);
        query = queries.get(0);
        assertThat(query.getType()).isEqualTo("sqlo");
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);
    }

    @Test
    public void shouldRollupFromGrandChildren() throws Exception {

        agentConfigDao.store("the gp::the parent::one", AgentConfig.newBuilder()
                .setAdvancedConfig(DEFAULT_ADVANCED_CONFIG)
                .build(), true);

        aggregateDao.truncateAll();
        List<Aggregate.SharedQueryText> sharedQueryText = ImmutableList
                .of(Aggregate.SharedQueryText.newBuilder().setFullText("select 1").build());
        aggregateDao.store("the gp::the parent::one", 60000, createData(), sharedQueryText);
        aggregateDao.store("the gp::the parent::one", 120000, createData(), sharedQueryText);
        aggregateDao.store("the gp::the parent::one", 360000, createData(), sharedQueryText);

        // rollup
        aggregateDao.rollup("the gp::the parent::");
        aggregateDao.rollup("the gp::");

        // check level-0 rolled up from children data
        SummaryQuery summaryQuery = ImmutableSummaryQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();
        AggregateQuery aggregateQuery = ImmutableAggregateQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();

        OverallSummaryCollector overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("the gp::", summaryQuery, overallSummaryCollector);
        OverallSummary overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        TransactionNameSummaryCollector transactionNameSummaryCollector =
                new TransactionNameSummaryCollector();
        SummarySortOrder sortOrder = SummarySortOrder.TOTAL_TIME;
        aggregateDao.mergeTransactionNameSummariesInto("the gp::", summaryQuery, sortOrder, 10,
                transactionNameSummaryCollector);
        Result<TransactionNameSummary> result =
                transactionNameSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        OverallErrorSummaryCollector overallErrorSummaryCollector =
                new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("the gp::", summaryQuery,
                overallErrorSummaryCollector);
        OverallErrorSummary overallErrorSummary =
                overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        TransactionNameErrorSummaryCollector errorSummaryCollector =
                new TransactionNameErrorSummaryCollector();
        ErrorSummarySortOrder errorSortOrder = ErrorSummarySortOrder.ERROR_COUNT;
        aggregateDao.mergeTransactionNameErrorSummariesInto("the gp::", summaryQuery,
                errorSortOrder,
                10, errorSummaryCollector);
        Result<TransactionNameErrorSummary> errorSummaryResult =
                errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        List<OverviewAggregate> overviewAggregates =
                aggregateDao.readOverviewAggregates("the gp::", aggregateQuery);
        assertThat(overviewAggregates).hasSize(2);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(overviewAggregates.get(1).transactionCount()).isEqualTo(3);

        List<PercentileAggregate> percentileAggregates =
                aggregateDao.readPercentileAggregates("the gp::", aggregateQuery);
        assertThat(percentileAggregates).hasSize(2);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(percentileAggregates.get(1).transactionCount()).isEqualTo(3);

        List<ThroughputAggregate> throughputAggregates =
                aggregateDao.readThroughputAggregates("the gp::", aggregateQuery);
        assertThat(throughputAggregates).hasSize(2);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(throughputAggregates.get(1).transactionCount()).isEqualTo(3);

        QueryCollector queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("the gp::", aggregateQuery, queryCollector);
        List<MutableQuery> queries = queryCollector.getSortedAndTruncatedQueries();
        assertThat(queries).hasSize(1);
        MutableQuery query = queries.get(0);
        assertThat(query.getType()).isEqualTo("sqlo");
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);

        // check rolled-up data after rollup
        summaryQuery = ImmutableSummaryQuery.builder()
                .copyFrom(summaryQuery)
                .rollupLevel(1)
                .build();
        aggregateQuery = ImmutableAggregateQuery.builder()
                .copyFrom(aggregateQuery)
                .rollupLevel(1)
                .build();

        overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("the gp::", summaryQuery, overallSummaryCollector);
        overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        transactionNameSummaryCollector = new TransactionNameSummaryCollector();
        aggregateDao.mergeTransactionNameSummariesInto("the gp::", summaryQuery, sortOrder, 10,
                transactionNameSummaryCollector);
        result = transactionNameSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        overallErrorSummaryCollector = new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("the gp::", summaryQuery,
                overallErrorSummaryCollector);
        overallErrorSummary = overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        errorSummaryCollector = new TransactionNameErrorSummaryCollector();
        aggregateDao.mergeTransactionNameErrorSummariesInto("the gp::", summaryQuery,
                errorSortOrder,
                10, errorSummaryCollector);
        errorSummaryResult = errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        overviewAggregates = aggregateDao.readOverviewAggregates("the gp::", aggregateQuery);
        assertThat(overviewAggregates).hasSize(1);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(6);

        percentileAggregates =
                aggregateDao.readPercentileAggregates("the gp::", aggregateQuery);
        assertThat(percentileAggregates).hasSize(1);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(6);

        throughputAggregates =
                aggregateDao.readThroughputAggregates("the gp::", aggregateQuery);
        assertThat(throughputAggregates).hasSize(1);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(6);

        queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("the gp::", aggregateQuery, queryCollector);
        queries = queryCollector.getSortedAndTruncatedQueries();
        assertThat(queries).hasSize(1);
        query = queries.get(0);
        assertThat(query.getType()).isEqualTo("sqlo");
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);
    }

    private static List<OldAggregatesByType> createData() {
        List<OldAggregatesByType> aggregatesByType = new ArrayList<>();
        aggregatesByType.add(OldAggregatesByType.newBuilder()
                .setTransactionType("tt0")
                .setOverallAggregate(createOverallAggregate())
                .addTransactionAggregate(createTransactionAggregate1())
                .addTransactionAggregate(createTransactionAggregate2())
                .build());
        aggregatesByType.add(OldAggregatesByType.newBuilder()
                .setTransactionType("tt1")
                .setOverallAggregate(createOverallAggregate())
                .addTransactionAggregate(createTransactionAggregate1())
                .addTransactionAggregate(createTransactionAggregate2())
                .build());
        return aggregatesByType;
    }

    private static Aggregate createOverallAggregate() {
        return Aggregate.newBuilder()
                .setTotalDurationNanos(3579)
                .setTransactionCount(3)
                .setErrorCount(1)
                .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                        .setName("abc")
                        .setTotalNanos(333)
                        .setCount(3))
                .setAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                        .setName("auxiliary thread")
                        .setTotalNanos(666)
                        .setCount(3))
                .addAsyncTimer(Aggregate.Timer.newBuilder()
                        .setName("mnm")
                        .setTotalNanos(999)
                        .setCount(3))
                .addQuery(Aggregate.Query.newBuilder()
                        .setType("sqlo")
                        .setSharedQueryTextIndex(0)
                        .setTotalDurationNanos(7)
                        .setTotalRows(OptionalInt64.newBuilder().setValue(5))
                        .setExecutionCount(2))
                .build();
    }

    private static OldTransactionAggregate createTransactionAggregate1() {
        return OldTransactionAggregate.newBuilder()
                .setTransactionName("tn1")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(1234)
                        .setTransactionCount(1)
                        .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("abc")
                                .setTotalNanos(111)
                                .setCount(1))
                        .setAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("auxiliary thread")
                                .setTotalNanos(222)
                                .setCount(1))
                        .addAsyncTimer(Aggregate.Timer.newBuilder()
                                .setName("mnm")
                                .setTotalNanos(333)
                                .setCount(1))
                        .addQuery(Aggregate.Query.newBuilder()
                                .setType("sqlo")
                                .setSharedQueryTextIndex(0)
                                .setTotalDurationNanos(7)
                                .setTotalRows(OptionalInt64.newBuilder().setValue(5))
                                .setExecutionCount(2)))
                .build();
    }

    private static OldTransactionAggregate createTransactionAggregate2() {
        return OldTransactionAggregate.newBuilder()
                .setTransactionName("tn2")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(2345)
                        .setTransactionCount(2)
                        .setErrorCount(1)
                        .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("abc")
                                .setTotalNanos(222)
                                .setCount(2))
                        .setAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("auxiliary thread")
                                .setTotalNanos(444)
                                .setCount(2))
                        .addAsyncTimer(Aggregate.Timer.newBuilder()
                                .setName("mnm")
                                .setTotalNanos(666)
                                .setCount(2)))
                .build();
    }
}
