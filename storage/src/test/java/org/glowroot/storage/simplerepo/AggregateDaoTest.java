/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.storage.simplerepo;

import java.io.File;
import java.util.List;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionSummary;
import org.glowroot.common.util.Styles;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.AggregateRepository.TransactionSummaryQuery;
import org.glowroot.storage.repo.AggregateRepository.TransactionSummarySortOrder;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.ImmutableRollupConfig;
import org.glowroot.storage.repo.ImmutableTransactionSummaryQuery;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.config.ImmutableStorageConfig;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.storage.simplerepo.util.CappedDatabase;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.Schema;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OverallAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Styles.Private
public class AggregateDaoTest {

    private static final String SERVER_ID = "";

    private DataSource dataSource;
    private File cappedFile;
    private CappedDatabase cappedDatabase;
    private AggregateDao aggregateDao;

    @Before
    public void beforeEachTest() throws Exception {
        dataSource = DataSource.createH2InMemory();
        Schema schema = dataSource.getSchema();
        if (schema.tableExists("overall_point")) {
            dataSource.execute("drop table overall_point");
        }
        if (schema.tableExists("transaction_point")) {
            dataSource.execute("drop table transaction_point");
        }
        cappedFile = File.createTempFile("glowroot-test-", ".capped.db");
        cappedDatabase = new CappedDatabase(cappedFile, 1000000, Ticker.systemTicker());
        ConfigRepository configRepository = mock(ConfigRepository.class);
        when(configRepository.getStorageConfig()).thenReturn(ImmutableStorageConfig.builder()
                .rollupExpirationHours(
                        ImmutableList.of(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE))
                .build());
        when(configRepository.getAdvancedConfig(SERVER_ID))
                .thenReturn(ImmutableAdvancedConfig.builder().build());
        ImmutableList<RollupConfig> rollupConfigs = ImmutableList.<RollupConfig>of(
                ImmutableRollupConfig.of(1000, 0), ImmutableRollupConfig.of(15000, 3600000),
                ImmutableRollupConfig.of(900000000, 8 * 3600000));
        when(configRepository.getRollupConfigs()).thenReturn(rollupConfigs);
        aggregateDao = new AggregateDao(dataSource, ImmutableList.<CappedDatabase>of(),
                configRepository, mock(ServerDao.class), mock(TransactionTypeDao.class),
                mock(RollupLevelService.class));
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
        List<OverviewAggregate> overallAggregates =
                aggregateDao.readOverallOverviewAggregates(SERVER_ID, "a type", 0, 100000, 0);
        TransactionSummaryQuery query = ImmutableTransactionSummaryQuery.builder()
                .serverRollup(SERVER_ID)
                .transactionType("a type")
                .from(0)
                .to(100000)
                .sortOrder(TransactionSummarySortOrder.TOTAL_TIME)
                .limit(10)
                .build();
        Result<TransactionSummary> queryResult = aggregateDao.readTransactionSummaries(query);
        // then
        assertThat(overallAggregates).hasSize(2);
        assertThat(queryResult.records()).hasSize(3);
        assertThat(queryResult.records().get(0).transactionName()).isEqualTo("seven");
        assertThat(queryResult.records().get(0).totalNanos()).isEqualTo(2800000);
        assertThat(queryResult.records().get(0).transactionCount()).isEqualTo(14);
        assertThat(queryResult.records().get(1).transactionName()).isEqualTo("two");
        assertThat(queryResult.records().get(1).totalNanos()).isEqualTo(600000);
        assertThat(queryResult.records().get(1).transactionCount()).isEqualTo(4);
        assertThat(queryResult.records().get(2).transactionName()).isEqualTo("one");
        assertThat(queryResult.records().get(2).totalNanos()).isEqualTo(200000);
        assertThat(queryResult.records().get(2).transactionCount()).isEqualTo(2);
    }

    // also used by TransactionCommonServiceTest
    public void populateAggregates() throws Exception {
        OverallAggregate overallAggregate = OverallAggregate.newBuilder()
                .setTransactionType("a type")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalNanos(1000000)
                        .setErrorCount(0)
                        .setTransactionCount(10)
                        .setTotalNanosHistogram(getFakeHistogram())
                        .build())
                .build();
        List<TransactionAggregate> transactionAggregates = Lists.newArrayList();
        transactionAggregates.add(TransactionAggregate.newBuilder()
                .setTransactionType("a type")
                .setTransactionName("one")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalNanos(100000)
                        .setErrorCount(0)
                        .setTransactionCount(1)
                        .setTotalNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        transactionAggregates.add(TransactionAggregate.newBuilder()
                .setTransactionType("a type")
                .setTransactionName("two")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalNanos(300000)
                        .setErrorCount(0)
                        .setTransactionCount(2)
                        .setTotalNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        transactionAggregates.add(TransactionAggregate.newBuilder()
                .setTransactionType("a type")
                .setTransactionName("seven")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalNanos(1400000)
                        .setErrorCount(0)
                        .setTransactionCount(7)
                        .setTotalNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        aggregateDao.store(SERVER_ID, 10000, ImmutableList.of(overallAggregate),
                transactionAggregates);

        OverallAggregate overallAggregate2 = OverallAggregate.newBuilder()
                .setTransactionType("a type")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalNanos(1000000)
                        .setErrorCount(0)
                        .setTransactionCount(10)
                        .setTotalNanosHistogram(getFakeHistogram())
                        .build())
                .build();
        List<TransactionAggregate> transactionAggregates2 = Lists.newArrayList();
        transactionAggregates2.add(TransactionAggregate.newBuilder()
                .setTransactionType("a type")
                .setTransactionName("one")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalNanos(100000)
                        .setErrorCount(0)
                        .setTransactionCount(1)
                        .setTotalNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        transactionAggregates2.add(TransactionAggregate.newBuilder()
                .setTransactionType("a type")
                .setTransactionName("two")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalNanos(300000)
                        .setErrorCount(0)
                        .setTransactionCount(2)
                        .setTotalNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        transactionAggregates2.add(TransactionAggregate.newBuilder()
                .setTransactionType("a type")
                .setTransactionName("seven")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalNanos(1400000)
                        .setErrorCount(0)
                        .setTransactionCount(7)
                        .setTotalNanosHistogram(getFakeHistogram())
                        .build())
                .build());
        aggregateDao.store(SERVER_ID, 20000, ImmutableList.of(overallAggregate2),
                transactionAggregates2);
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
