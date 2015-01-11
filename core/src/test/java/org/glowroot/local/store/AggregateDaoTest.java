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
package org.glowroot.local.store;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.ImmutableAggregate;
import org.glowroot.common.Ticker;
import org.glowroot.local.store.AggregateDao.TransactionSummarySortOrder;

import static org.assertj.core.api.Assertions.assertThat;

public class AggregateDaoTest {

    private DataSource dataSource;
    private File cappedFile;
    private ScheduledExecutorService scheduledExecutor;
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
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        cappedDatabase = new CappedDatabase(cappedFile, 1000000, scheduledExecutor,
                Ticker.systemTicker());
        aggregateDao = new AggregateDao(dataSource, cappedDatabase);
    }

    @After
    public void afterEachTest() throws Exception {
        scheduledExecutor.shutdownNow();
        dataSource.close();
        cappedDatabase.close();
        cappedFile.delete();
    }

    @Test
    public void shouldReadTransactions() throws SQLException {
        // given
        Aggregate overallAggregate = ImmutableAggregate.builder()
                .transactionType("a type")
                .transactionName(null)
                .captureTime(10000)
                .totalMicros(1000000)
                .errorCount(0)
                .transactionCount(10)
                .metrics("")
                .histogram(new byte[0])
                .profileSampleCount(0)
                .build();
        List<Aggregate> transactionAggregates = Lists.newArrayList();
        transactionAggregates.add(ImmutableAggregate.builder()
                .transactionType("a type")
                .transactionName("one")
                .captureTime(10000)
                .totalMicros(100000)
                .errorCount(0)
                .transactionCount(1)
                .metrics("")
                .histogram(new byte[0])
                .profileSampleCount(0)
                .build());
        transactionAggregates.add(ImmutableAggregate.builder()
                .transactionType("a type")
                .transactionName("two")
                .captureTime(10000)
                .totalMicros(300000)
                .errorCount(0)
                .transactionCount(2)
                .metrics("")
                .histogram(new byte[0])
                .profileSampleCount(0)
                .build());
        transactionAggregates.add(ImmutableAggregate.builder()
                .transactionType("a type")
                .transactionName("seven")
                .captureTime(10000)
                .totalMicros(1400000)
                .errorCount(0)
                .transactionCount(7)
                .metrics("")
                .histogram(new byte[0])
                .profileSampleCount(0)
                .build());
        aggregateDao.store(ImmutableList.of(overallAggregate), transactionAggregates);

        Aggregate overallAggregate2 = ImmutableAggregate.builder()
                .transactionType("a type")
                .transactionName(null)
                .captureTime(20000)
                .totalMicros(1000000)
                .errorCount(0)
                .transactionCount(10)
                .metrics("")
                .histogram(new byte[0])
                .profileSampleCount(0)
                .build();
        List<Aggregate> transactionAggregates2 = Lists.newArrayList();
        transactionAggregates2.add(ImmutableAggregate.builder()
                .transactionType("a type")
                .transactionName("one")
                .captureTime(20000)
                .totalMicros(100000)
                .errorCount(0)
                .transactionCount(1)
                .metrics("")
                .histogram(new byte[0])
                .profileSampleCount(0)
                .build());
        transactionAggregates2.add(ImmutableAggregate.builder()
                .transactionType("a type")
                .transactionName("two")
                .captureTime(20000)
                .totalMicros(300000)
                .errorCount(0)
                .transactionCount(2)
                .metrics("")
                .histogram(new byte[0])
                .profileSampleCount(0)
                .build());
        transactionAggregates2.add(ImmutableAggregate.builder()
                .transactionType("a type")
                .transactionName("seven")
                .captureTime(20000)
                .totalMicros(1400000)
                .errorCount(0)
                .transactionCount(7)
                .metrics("")
                .histogram(new byte[0])
                .profileSampleCount(0)
                .build());

        aggregateDao.store(ImmutableList.of(overallAggregate2), transactionAggregates2);
        // when
        List<Aggregate> overallAggregates = aggregateDao.readOverallAggregates("a type", 0, 100000);
        ImmutableTransactionSummaryQuery query = ImmutableTransactionSummaryQuery.builder()
                .transactionType("a type")
                .from(0)
                .to(100000)
                .sortOrder(TransactionSummarySortOrder.TOTAL_TIME)
                .limit(10)
                .build();
        QueryResult<TransactionSummary> queryResult =
                aggregateDao.readTransactionSummaries(query);
        // then
        assertThat(overallAggregates).hasSize(2);
        assertThat(queryResult.records()).hasSize(3);
        assertThat(queryResult.records().get(0).transactionName()).isEqualTo("seven");
        assertThat(queryResult.records().get(0).totalMicros()).isEqualTo(2800000);
        assertThat(queryResult.records().get(0).transactionCount()).isEqualTo(14);
        assertThat(queryResult.records().get(1).transactionName()).isEqualTo("two");
        assertThat(queryResult.records().get(1).totalMicros()).isEqualTo(600000);
        assertThat(queryResult.records().get(1).transactionCount()).isEqualTo(4);
        assertThat(queryResult.records().get(2).transactionName()).isEqualTo("one");
        assertThat(queryResult.records().get(2).totalMicros()).isEqualTo(200000);
        assertThat(queryResult.records().get(2).transactionCount()).isEqualTo(2);
    }
}
