/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.fat.storage;

import java.io.File;
import java.util.List;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.agent.fat.storage.util.CappedDatabase;
import org.glowroot.agent.fat.storage.util.DataSource;
import org.glowroot.common.util.Styles;
import org.glowroot.storage.config.ImmutableStorageConfig;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.AggregateRepository.OverallQuery;
import org.glowroot.storage.repo.AggregateRepository.OverviewAggregate;
import org.glowroot.storage.repo.AggregateRepository.SummarySortOrder;
import org.glowroot.storage.repo.AggregateRepository.TransactionQuery;
import org.glowroot.storage.repo.AggregateRepository.TransactionSummary;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.ImmutableOverallQuery;
import org.glowroot.storage.repo.ImmutableRollupConfig;
import org.glowroot.storage.repo.ImmutableTransactionQuery;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TransactionSummaryCollector;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Styles.Private
public class AggregateDaoTest {

    private static final String AGENT_ID = "";

    private DataSource dataSource;
    private File cappedFile;
    private CappedDatabase cappedDatabase;
    private AggregateDao aggregateDao;

    @Before
    public void beforeEachTest() throws Exception {
        dataSource = new DataSource();
        if (dataSource.tableExists("overall_point")) {
            dataSource.execute("drop table overall_point");
        }
        if (dataSource.tableExists("transaction_point")) {
            dataSource.execute("drop table transaction_point");
        }
        cappedFile = File.createTempFile("glowroot-test-", ".capped.db");
        cappedDatabase = new CappedDatabase(cappedFile, 1000000, Ticker.systemTicker());
        ConfigRepository configRepository = mock(ConfigRepository.class);
        when(configRepository.getStorageConfig()).thenReturn(ImmutableStorageConfig.builder()
                .rollupExpirationHours(
                        ImmutableList.of(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE))
                .build());
        when(configRepository.getAdvancedConfig(AGENT_ID))
                .thenReturn(AdvancedConfig.getDefaultInstance());
        ImmutableList<RollupConfig> rollupConfigs = ImmutableList.<RollupConfig>of(
                ImmutableRollupConfig.of(1000, 0), ImmutableRollupConfig.of(15000, 3600000),
                ImmutableRollupConfig.of(900000000, 8 * 3600000));
        when(configRepository.getRollupConfigs()).thenReturn(rollupConfigs);
        aggregateDao = new AggregateDao(dataSource, ImmutableList.<CappedDatabase>of(),
                configRepository, mock(TransactionTypeDao.class));
    }

    @After
    public void afterEachTest() throws Exception {
        dataSource.close();
        cappedDatabase.close();
        cappedFile.delete();
    }

    @Test
    public void shouldReadTransactions() throws Exception {
        // given
        populateAggregates();
        // when
        TransactionQuery query = ImmutableTransactionQuery.builder()
                .agentRollup(AGENT_ID)
                .transactionType("a type")
                .from(0)
                .to(100000)
                .rollupLevel(0)
                .build();
        OverallQuery query2 = ImmutableOverallQuery.builder()
                .agentRollup(AGENT_ID)
                .transactionType("a type")
                .from(0)
                .to(100000)
                .rollupLevel(0)
                .build();
        TransactionSummaryCollector mergedTransactionSummaries = new TransactionSummaryCollector();
        List<OverviewAggregate> overallAggregates = aggregateDao.readOverviewAggregates(query);
        aggregateDao.mergeInTransactionSummaries(mergedTransactionSummaries, query2,
                SummarySortOrder.TOTAL_TIME, 10);
        Result<TransactionSummary> queryResult =
                mergedTransactionSummaries.getResult(SummarySortOrder.TOTAL_TIME, 10);
        // then
        assertThat(overallAggregates).hasSize(2);
        assertThat(queryResult.records()).hasSize(3);
        assertThat(queryResult.records().get(0).transactionName()).isEqualTo("seven");
        assertThat(queryResult.records().get(0).totalDurationNanos()).isEqualTo(2800000);
        assertThat(queryResult.records().get(0).transactionCount()).isEqualTo(14);
        assertThat(queryResult.records().get(1).transactionName()).isEqualTo("two");
        assertThat(queryResult.records().get(1).totalDurationNanos()).isEqualTo(600000);
        assertThat(queryResult.records().get(1).transactionCount()).isEqualTo(4);
        assertThat(queryResult.records().get(2).transactionName()).isEqualTo("one");
        assertThat(queryResult.records().get(2).totalDurationNanos()).isEqualTo(200000);
        assertThat(queryResult.records().get(2).transactionCount()).isEqualTo(2);
    }

    // also used by TransactionCommonServiceTest
    public void populateAggregates() throws Exception {
        Aggregate overallAggregate = Aggregate.newBuilder()
                .setTotalDurationNanos(1000000)
                .setErrorCount(0)
                .setTransactionCount(10)
                .setTotalDurationNanosHistogram(getFakeHistogram())
                .build();
        List<TransactionAggregate> transactionAggregates = Lists.newArrayList();
        transactionAggregates.add(TransactionAggregate.newBuilder()
                .setTransactionName("one")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(100000)
                        .setErrorCount(0)
                        .setTransactionCount(1)
                        .setTotalDurationNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        transactionAggregates.add(TransactionAggregate.newBuilder()
                .setTransactionName("two")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(300000)
                        .setErrorCount(0)
                        .setTransactionCount(2)
                        .setTotalDurationNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        transactionAggregates.add(TransactionAggregate.newBuilder()
                .setTransactionName("seven")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(1400000)
                        .setErrorCount(0)
                        .setTransactionCount(7)
                        .setTotalDurationNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        AggregatesByType aggregatesByType = AggregatesByType.newBuilder()
                .setTransactionType("a type")
                .setOverallAggregate(overallAggregate)
                .addAllTransactionAggregate(transactionAggregates)
                .build();
        aggregateDao.store(AGENT_ID, 10000, ImmutableList.of(aggregatesByType));

        List<TransactionAggregate> transactionAggregates2 = Lists.newArrayList();
        transactionAggregates2.add(TransactionAggregate.newBuilder()
                .setTransactionName("one")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(100000)
                        .setErrorCount(0)
                        .setTransactionCount(1)
                        .setTotalDurationNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        transactionAggregates2.add(TransactionAggregate.newBuilder()
                .setTransactionName("two")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(300000)
                        .setErrorCount(0)
                        .setTransactionCount(2)
                        .setTotalDurationNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        transactionAggregates2.add(TransactionAggregate.newBuilder()
                .setTransactionName("seven")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(1400000)
                        .setErrorCount(0)
                        .setTransactionCount(7)
                        .setTotalDurationNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        AggregatesByType aggregatesByType2 = AggregatesByType.newBuilder()
                .setTransactionType("a type")
                .setOverallAggregate(overallAggregate)
                .addAllTransactionAggregate(transactionAggregates2)
                .build();
        aggregateDao.store(AGENT_ID, 20000, ImmutableList.of(aggregatesByType2));
    }

    // used by TransactionCommonServiceTest
    public AggregateRepository getAggregateRepository() {
        return aggregateDao;
    }

    private static Aggregate.Histogram getFakeHistogram() {
        return Aggregate.Histogram.newBuilder()
                .addOrderedRawValue(123)
                .addOrderedRawValue(456)
                .build();
    }
}
