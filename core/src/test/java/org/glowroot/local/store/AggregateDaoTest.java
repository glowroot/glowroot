/*
 * Copyright 2013-2014 the original author or authors.
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
import org.glowroot.collector.Existence;
import org.glowroot.common.Ticker;

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
        Aggregate overallAggregate =
                new Aggregate("a type", null, 10000, 1000000, 0, 10, "", Existence.NO, 0, null);
        List<Aggregate> transactionAggregates = Lists.newArrayList();
        transactionAggregates.add(new Aggregate("a type", "one", 10000, 100000, 0, 1, "",
                Existence.NO, 0, null));
        transactionAggregates.add(new Aggregate("a type", "two", 10000, 300000, 0, 2, "",
                Existence.NO, 0, null));
        transactionAggregates.add(new Aggregate("a type", "seven", 10000, 1400000, 0, 7, "",
                Existence.NO, 0, null));
        aggregateDao.store(ImmutableList.of(overallAggregate), transactionAggregates);

        Aggregate overallPoint2 =
                new Aggregate("a type", null, 20000, 1000000, 0, 10, "", Existence.NO, 0, null);
        List<Aggregate> aggregates2 = Lists.newArrayList();
        aggregates2.add(new Aggregate("a type", "one", 20000, 100000, 0, 1, "", Existence.NO, 0,
                null));
        aggregates2.add(new Aggregate("a type", "two", 20000, 300000, 0, 2, "", Existence.NO, 0,
                null));
        aggregates2.add(new Aggregate("a type", "seven", 20000, 1400000, 0, 7, "", Existence.NO, 0,
                null));
        aggregateDao.store(ImmutableList.of(overallPoint2), aggregates2);
        // when
        List<Aggregate> overallAggregates = aggregateDao.readOverallAggregates("a type", 0, 100000);
        QueryResult<Summary> queryResult =
                aggregateDao.readTransactionSummaries("a type", 0, 100000, 10);
        // then
        assertThat(overallAggregates).hasSize(2);
        assertThat(queryResult.getRecords()).hasSize(3);
        assertThat(queryResult.getRecords().get(0).getTransactionName()).isEqualTo("seven");
        assertThat(queryResult.getRecords().get(0).getTotalMicros()).isEqualTo(2800000);
        assertThat(queryResult.getRecords().get(0).getTransactionCount()).isEqualTo(14);
        assertThat(queryResult.getRecords().get(1).getTransactionName()).isEqualTo("two");
        assertThat(queryResult.getRecords().get(1).getTotalMicros()).isEqualTo(600000);
        assertThat(queryResult.getRecords().get(1).getTransactionCount()).isEqualTo(4);
        assertThat(queryResult.getRecords().get(2).getTransactionName()).isEqualTo("one");
        assertThat(queryResult.getRecords().get(2).getTotalMicros()).isEqualTo(200000);
        assertThat(queryResult.getRecords().get(2).getTransactionCount()).isEqualTo(2);
    }
}
