/*
 * Copyright 2013-2018 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.agent.collector.Collector.AggregateReader;
import org.glowroot.agent.collector.Collector.AggregateVisitor;
import org.glowroot.agent.embedded.util.CappedDatabase;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.common.live.ImmutableOverallQuery;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.Result;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector.SummarySortOrder;
import org.glowroot.common.model.TransactionSummaryCollector.TransactionSummary;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.ImmutableRollupConfig;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// this is not an integration test (*IT.java) since then it would run against shaded agent and fail
// due to shading issues
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
        ConfigRepositoryImpl configRepository = mock(ConfigRepositoryImpl.class);
        when(configRepository.getAdvancedConfig(AGENT_ID))
                .thenReturn(AdvancedConfig.getDefaultInstance());
        ImmutableList<RollupConfig> rollupConfigs = ImmutableList.<RollupConfig>of(
                ImmutableRollupConfig.of(1000, 0), ImmutableRollupConfig.of(15000, 3600000),
                ImmutableRollupConfig.of(900000000, 8 * 3600000));
        when(configRepository.getRollupConfigs()).thenReturn(rollupConfigs);
        aggregateDao = new AggregateDao(
                dataSource, ImmutableList.<CappedDatabase>of(cappedDatabase, cappedDatabase,
                        cappedDatabase, cappedDatabase),
                configRepository, mock(TransactionTypeDao.class), mock(FullQueryTextDao.class));
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
                .transactionType("a type")
                .from(0)
                .to(100000)
                .rollupLevel(0)
                .build();
        OverallQuery query2 = ImmutableOverallQuery.builder()
                .transactionType("a type")
                .from(0)
                .to(100000)
                .rollupLevel(0)
                .build();
        TransactionSummaryCollector collector = new TransactionSummaryCollector();
        List<OverviewAggregate> overallAggregates =
                aggregateDao.readOverviewAggregates(AGENT_ID, query);
        aggregateDao.mergeTransactionSummariesInto(AGENT_ID, query2, SummarySortOrder.TOTAL_TIME,
                10, collector);
        Result<TransactionSummary> queryResult =
                collector.getResult(SummarySortOrder.TOTAL_TIME, 10);

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
        aggregateDao.store(new AggregateReader() {
            @Override
            public long captureTime() {
                return 10000;
            }
            @Override
            public void accept(AggregateVisitor aggregateVisitor) throws Exception {
                aggregateVisitor.visitOverallAggregate("a type", new ArrayList<String>(),
                        Aggregate.newBuilder()
                                .setTotalDurationNanos(1000000)
                                .setErrorCount(0)
                                .setTransactionCount(10)
                                .setDurationNanosHistogram(getFakeHistogram())
                                .build());
                aggregateVisitor.visitTransactionAggregate("a type", "one", new ArrayList<String>(),
                        Aggregate.newBuilder()
                                .setTotalDurationNanos(100000)
                                .setErrorCount(0)
                                .setTransactionCount(1)
                                .setDurationNanosHistogram(getFakeHistogram())
                                .build());
                aggregateVisitor.visitTransactionAggregate("a type", "two", new ArrayList<String>(),
                        Aggregate.newBuilder()
                                .setTotalDurationNanos(300000)
                                .setErrorCount(0)
                                .setTransactionCount(2)
                                .setDurationNanosHistogram(getFakeHistogram())
                                .build());
                aggregateVisitor.visitTransactionAggregate("a type", "seven",
                        new ArrayList<String>(), Aggregate.newBuilder()
                                .setTotalDurationNanos(1400000)
                                .setErrorCount(0)
                                .setTransactionCount(7)
                                .setDurationNanosHistogram(getFakeHistogram())
                                .build());
            }
        });

        aggregateDao.store(new AggregateReader() {
            @Override
            public long captureTime() {
                return 20000;
            }
            @Override
            public void accept(AggregateVisitor aggregateVisitor) throws Exception {
                aggregateVisitor.visitOverallAggregate("a type", new ArrayList<String>(),
                        Aggregate.newBuilder()
                                .setTotalDurationNanos(1000000)
                                .setErrorCount(0)
                                .setTransactionCount(10)
                                .setDurationNanosHistogram(getFakeHistogram())
                                .build());
                aggregateVisitor.visitTransactionAggregate("a type", "one", new ArrayList<String>(),
                        Aggregate.newBuilder()
                                .setTotalDurationNanos(100000)
                                .setErrorCount(0)
                                .setTransactionCount(1)
                                .setDurationNanosHistogram(getFakeHistogram())
                                .build());
                aggregateVisitor.visitTransactionAggregate("a type", "two", new ArrayList<String>(),
                        Aggregate.newBuilder()
                                .setTotalDurationNanos(300000)
                                .setErrorCount(0)
                                .setTransactionCount(2)
                                .setDurationNanosHistogram(getFakeHistogram())
                                .build());
                aggregateVisitor.visitTransactionAggregate("a type", "seven",
                        new ArrayList<String>(), Aggregate.newBuilder()
                                .setTotalDurationNanos(1400000)
                                .setErrorCount(0)
                                .setTransactionCount(7)
                                .setDurationNanosHistogram(getFakeHistogram())
                                .build());
            }
        });
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
