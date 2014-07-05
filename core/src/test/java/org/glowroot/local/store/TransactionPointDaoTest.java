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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.collect.Maps;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.collector.Existence;
import org.glowroot.collector.TransactionPoint;
import org.glowroot.common.Ticker;
import org.glowroot.local.store.TransactionSummaryQuery.TransactionSortAttribute;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TransactionPointDaoTest {

    private DataSource dataSource;
    private File cappedFile;
    private ScheduledExecutorService scheduledExecutor;
    private CappedDatabase cappedDatabase;
    private TransactionPointDao transactionPointDao;

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
        transactionPointDao = new TransactionPointDao(dataSource, cappedDatabase);
    }

    @After
    public void afterEachTest() throws Exception {
        scheduledExecutor.shutdownNow();
        dataSource.close();
        cappedDatabase.close();
        cappedFile.delete();
    }

    @Test
    public void shouldReadTransactions() {
        // given
        TransactionPoint overallPoint =
                new TransactionPoint(10000, 1000000, 10, 0, 0, "", Existence.NO, null);
        Map<String, TransactionPoint> transactionPoints = Maps.newHashMap();
        transactionPoints.put("one",
                new TransactionPoint(10000, 100000, 1, 0, 0, "", Existence.NO, null));
        transactionPoints.put("two",
                new TransactionPoint(10000, 300000, 2, 0, 0, "", Existence.NO, null));
        transactionPoints.put("seven",
                new TransactionPoint(10000, 1400000, 7, 0, 0, "", Existence.NO, null));
        transactionPointDao.store("", overallPoint, transactionPoints);

        TransactionPoint overallPoint2 =
                new TransactionPoint(20000, 1000000, 10, 0, 0, "", Existence.NO, null);
        Map<String, TransactionPoint> transactionPoints2 = Maps.newHashMap();
        transactionPoints2.put("one",
                new TransactionPoint(20000, 100000, 1, 0, 0, "", Existence.NO, null));
        transactionPoints2.put("two",
                new TransactionPoint(20000, 300000, 2, 0, 0, "", Existence.NO, null));
        transactionPoints2.put("seven",
                new TransactionPoint(20000, 1400000, 7, 0, 0, "", Existence.NO, null));
        transactionPointDao.store("", overallPoint2, transactionPoints2);
        // when
        List<TransactionPoint> overallPoints = transactionPointDao.readOverallPoints("", 0, 100000);
        TransactionSummaryQuery query = new TransactionSummaryQuery("", 0, 100000,
                TransactionSortAttribute.AVERAGE, SortDirection.DESC, 10);
        QueryResult<TransactionSummary> queryResult =
                transactionPointDao.readTransactionSummaries(query);
        // then
        assertThat(overallPoints).hasSize(2);
        assertThat(queryResult.getRecords()).hasSize(3);
        assertThat(queryResult.getRecords().get(0).getName()).isEqualTo("seven");
        assertThat(queryResult.getRecords().get(0).getTotalMicros()).isEqualTo(2800000);
        assertThat(queryResult.getRecords().get(0).getCount()).isEqualTo(14);
        assertThat(queryResult.getRecords().get(1).getName()).isEqualTo("two");
        assertThat(queryResult.getRecords().get(1).getTotalMicros()).isEqualTo(600000);
        assertThat(queryResult.getRecords().get(1).getCount()).isEqualTo(4);
        assertThat(queryResult.getRecords().get(2).getName()).isEqualTo("one");
        assertThat(queryResult.getRecords().get(2).getTotalMicros()).isEqualTo(200000);
        assertThat(queryResult.getRecords().get(2).getCount()).isEqualTo(2);
    }

    @Test
    public void shouldReadBgTransactions() {
        // given
        TransactionPoint overallPoint =
                new TransactionPoint(10000, 1000000, 10, 0, 0, "", Existence.NO, null);
        Map<String, TransactionPoint> transactionPoints = Maps.newHashMap();
        transactionPoints.put("one",
                new TransactionPoint(10000, 100000, 1, 0, 0, "", Existence.NO, null));
        transactionPoints.put("two",
                new TransactionPoint(10000, 300000, 2, 0, 0, "", Existence.NO, null));
        transactionPoints.put("seven",
                new TransactionPoint(10000, 1400000, 7, 0, 0, "", Existence.NO, null));
        transactionPointDao.store("bg", overallPoint, transactionPoints);
        TransactionPoint overallPoint2 =
                new TransactionPoint(20000, 1000000, 10, 0, 0, "", Existence.NO, null);
        Map<String, TransactionPoint> transactionPoints2 = Maps.newHashMap();
        transactionPoints2.put("one",
                new TransactionPoint(20000, 100000, 1, 0, 0, "", Existence.NO, null));
        transactionPoints2.put("two",
                new TransactionPoint(20000, 300000, 2, 0, 0, "", Existence.NO, null));
        transactionPoints2.put("seven",
                new TransactionPoint(20000, 1400000, 7, 0, 0, "", Existence.NO, null));
        transactionPointDao.store("bg", overallPoint2, transactionPoints2);
        // when
        List<TransactionPoint> overallPoints =
                transactionPointDao.readOverallPoints("bg", 0, 100000);
        TransactionSummaryQuery query = new TransactionSummaryQuery("bg", 0, 100000,
                TransactionSortAttribute.AVERAGE, SortDirection.DESC, 10);
        QueryResult<TransactionSummary> queryResult =
                transactionPointDao.readTransactionSummaries(query);
        // then
        assertThat(overallPoints).hasSize(2);
        assertThat(queryResult.getRecords()).hasSize(3);
        assertThat(queryResult.getRecords().get(0).getName()).isEqualTo("seven");
        assertThat(queryResult.getRecords().get(0).getTotalMicros()).isEqualTo(2800000);
        assertThat(queryResult.getRecords().get(0).getCount()).isEqualTo(14);
        assertThat(queryResult.getRecords().get(1).getName()).isEqualTo("two");
        assertThat(queryResult.getRecords().get(1).getTotalMicros()).isEqualTo(600000);
        assertThat(queryResult.getRecords().get(1).getCount()).isEqualTo(4);
        assertThat(queryResult.getRecords().get(2).getName()).isEqualTo("one");
        assertThat(queryResult.getRecords().get(2).getTotalMicros()).isEqualTo(200000);
        assertThat(queryResult.getRecords().get(2).getCount()).isEqualTo(2);
    }
}
