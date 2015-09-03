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
package org.glowroot.server.simplerepo;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.collector.spi.model.AggregateOuterClass.Aggregate;
import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Styles;
import org.glowroot.common.util.Tickers;
import org.glowroot.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.live.LiveAggregateRepository.TransactionSummary;
import org.glowroot.server.repo.AggregateRepository;
import org.glowroot.server.repo.AggregateRepository.TransactionSummaryQuery;
import org.glowroot.server.repo.AggregateRepository.TransactionSummarySortOrder;
import org.glowroot.server.repo.ConfigRepository;
import org.glowroot.server.repo.ConfigRepository.RollupConfig;
import org.glowroot.server.repo.ImmutableRollupConfig;
import org.glowroot.server.repo.ImmutableTransactionSummaryQuery;
import org.glowroot.server.repo.Result;
import org.glowroot.server.repo.config.ImmutableStorageConfig;
import org.glowroot.server.simplerepo.util.CappedDatabase;
import org.glowroot.server.simplerepo.util.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Styles.Private
public class AggregateDaoTest {

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
        cappedDatabase = new CappedDatabase(cappedFile, 1000000, Tickers.getTicker());
        ConfigRepository configRepository = mock(ConfigRepository.class);
        when(configRepository.getStorageConfig()).thenReturn(ImmutableStorageConfig.builder()
                .rollupExpirationHours(
                        ImmutableList.of(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE))
                .build());
        when(configRepository.getAdvancedConfig())
                .thenReturn(ImmutableAdvancedConfig.builder().build());
        ImmutableList<RollupConfig> rollupConfigs = ImmutableList.<RollupConfig>of(
                ImmutableRollupConfig.of(1000, 0), ImmutableRollupConfig.of(15000, 3600000),
                ImmutableRollupConfig.of(900000000, 8 * 3600000));
        when(configRepository.getRollupConfigs()).thenReturn(rollupConfigs);
        aggregateDao = new AggregateDao(dataSource, ImmutableList.<CappedDatabase>of(),
                configRepository, Clock.systemClock());
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
                aggregateDao.readOverallOverviewAggregates("a type", 0, 100000, 0);
        TransactionSummaryQuery query = ImmutableTransactionSummaryQuery.builder()
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
        Aggregate overallAggregate = Aggregate.newBuilder()
                .setCaptureTime(10000)
                .setTotalNanos(1000000)
                .setErrorCount(0)
                .setTransactionCount(10)
                .setTotalCpuNanos(-1)
                .setTotalBlockedNanos(-1)
                .setTotalWaitedNanos(-1)
                .setTotalAllocatedBytes(-1)
                .setTotalNanosHistogram(getFakeHistogram())
                .build();
        Map<String, Aggregate> transactionAggregates = Maps.newHashMap();
        transactionAggregates.put("one", Aggregate.newBuilder()
                .setCaptureTime(10000)
                .setTotalNanos(100000)
                .setErrorCount(0)
                .setTransactionCount(1)
                .setTotalCpuNanos(-1)
                .setTotalBlockedNanos(-1)
                .setTotalWaitedNanos(-1)
                .setTotalAllocatedBytes(-1)
                .setTotalNanosHistogram(getFakeHistogram())
                .build());
        transactionAggregates.put("two", Aggregate.newBuilder()
                .setCaptureTime(10000)
                .setTotalNanos(300000)
                .setErrorCount(0)
                .setTransactionCount(2)
                .setTotalCpuNanos(-1)
                .setTotalBlockedNanos(-1)
                .setTotalWaitedNanos(-1)
                .setTotalAllocatedBytes(-1)
                .setTotalNanosHistogram(getFakeHistogram())
                .build());
        transactionAggregates.put("seven", Aggregate.newBuilder()
                .setCaptureTime(10000)
                .setTotalNanos(1400000)
                .setErrorCount(0)
                .setTransactionCount(7)
                .setTotalCpuNanos(-1)
                .setTotalBlockedNanos(-1)
                .setTotalWaitedNanos(-1)
                .setTotalAllocatedBytes(-1)
                .setTotalNanosHistogram(getFakeHistogram())
                .build());
        aggregateDao.store(ImmutableMap.of("a type", overallAggregate),
                ImmutableMap.of("a type", transactionAggregates), 10000);

        Aggregate overallAggregate2 = Aggregate.newBuilder()
                .setCaptureTime(20000)
                .setTotalNanos(1000000)
                .setErrorCount(0)
                .setTransactionCount(10)
                .setTotalCpuNanos(-1)
                .setTotalBlockedNanos(-1)
                .setTotalWaitedNanos(-1)
                .setTotalAllocatedBytes(-1)
                .setTotalNanosHistogram(getFakeHistogram())
                .build();
        Map<String, Aggregate> transactionAggregates2 = Maps.newHashMap();
        transactionAggregates2.put("one", Aggregate.newBuilder()
                .setCaptureTime(20000)
                .setTotalNanos(100000)
                .setErrorCount(0)
                .setTransactionCount(1)
                .setTotalCpuNanos(-1)
                .setTotalBlockedNanos(-1)
                .setTotalWaitedNanos(-1)
                .setTotalAllocatedBytes(-1)
                .setTotalNanosHistogram(getFakeHistogram())
                .build());
        transactionAggregates2.put("two", Aggregate.newBuilder()
                .setCaptureTime(20000)
                .setTotalNanos(300000)
                .setErrorCount(0)
                .setTransactionCount(2)
                .setTotalCpuNanos(-1)
                .setTotalBlockedNanos(-1)
                .setTotalWaitedNanos(-1)
                .setTotalAllocatedBytes(-1)
                .setTotalNanosHistogram(getFakeHistogram())
                .build());
        transactionAggregates2.put("seven", Aggregate.newBuilder()
                .setCaptureTime(20000)
                .setTotalNanos(1400000)
                .setErrorCount(0)
                .setTransactionCount(7)
                .setTotalCpuNanos(-1)
                .setTotalBlockedNanos(-1)
                .setTotalWaitedNanos(-1)
                .setTotalAllocatedBytes(-1)
                .setTotalNanosHistogram(getFakeHistogram())
                .build());
        aggregateDao.store(ImmutableMap.of("a type", overallAggregate2),
                ImmutableMap.of("a type", transactionAggregates2), 20000);
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
