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

import java.util.List;
import java.util.Map;

import com.google.common.collect.Maps;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.collector.TransactionPoint;
import org.glowroot.local.store.TransactionPointDao.SortDirection;
import org.glowroot.local.store.TransactionPointDao.TransactionSortColumn;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TransactionPointDaoTest {

    private DataSource dataSource;
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
        transactionPointDao = new TransactionPointDao(dataSource);
    }

    @After
    public void afterEachTest() throws Exception {
        dataSource.close();
    }

    @Test
    public void shouldReadTransactions() {
        // given
        TransactionPoint overallPoint = new TransactionPoint(10000, 1000000, 10, 0, 0, "", null);
        Map<String, TransactionPoint> transactionPoints = Maps.newHashMap();
        transactionPoints.put("one", new TransactionPoint(10000, 100000, 1, 0, 0, "", null));
        transactionPoints.put("two", new TransactionPoint(10000, 300000, 2, 0, 0, "", null));
        transactionPoints.put("seven", new TransactionPoint(10000, 1400000, 7, 0, 0, "", null));
        transactionPointDao.store("", overallPoint, transactionPoints);

        TransactionPoint overallPoint2 = new TransactionPoint(20000, 1000000, 10, 0, 0, "", null);
        Map<String, TransactionPoint> transactionPoints2 = Maps.newHashMap();
        transactionPoints2.put("one", new TransactionPoint(20000, 100000, 1, 0, 0, "", null));
        transactionPoints2.put("two", new TransactionPoint(20000, 300000, 2, 0, 0, "", null));
        transactionPoints2.put("seven", new TransactionPoint(20000, 1400000, 7, 0, 0, "", null));
        transactionPointDao.store("", overallPoint2, transactionPoints2);
        // when
        List<TransactionPoint> overallPoints = transactionPointDao.readOverallPoints("", 0, 100000);
        List<Transaction> transactions = transactionPointDao.readTransactions("", 0,
                100000, TransactionSortColumn.AVERAGE, SortDirection.DESC, 10);
        // then
        assertThat(overallPoints).hasSize(2);
        assertThat(transactions).hasSize(3);
        assertThat(transactions.get(0).getName()).isEqualTo("seven");
        assertThat(transactions.get(0).getTotalMicros()).isEqualTo(2800000);
        assertThat(transactions.get(0).getCount()).isEqualTo(14);
        assertThat(transactions.get(1).getName()).isEqualTo("two");
        assertThat(transactions.get(1).getTotalMicros()).isEqualTo(600000);
        assertThat(transactions.get(1).getCount()).isEqualTo(4);
        assertThat(transactions.get(2).getName()).isEqualTo("one");
        assertThat(transactions.get(2).getTotalMicros()).isEqualTo(200000);
        assertThat(transactions.get(2).getCount()).isEqualTo(2);
    }

    @Test
    public void shouldReadBgTransactions() {
        // given
        TransactionPoint overallPoint = new TransactionPoint(10000, 1000000, 10, 0, 0, "", null);
        Map<String, TransactionPoint> transactionPoints = Maps.newHashMap();
        transactionPoints.put("one", new TransactionPoint(10000, 100000, 1, 0, 0, "", null));
        transactionPoints.put("two", new TransactionPoint(10000, 300000, 2, 0, 0, "", null));
        transactionPoints.put("seven", new TransactionPoint(10000, 1400000, 7, 0, 0, "", null));
        transactionPointDao.store("bg", overallPoint, transactionPoints);
        TransactionPoint overallPoint2 = new TransactionPoint(20000, 1000000, 10, 0, 0, "", null);
        Map<String, TransactionPoint> transactionPoints2 = Maps.newHashMap();
        transactionPoints2.put("one", new TransactionPoint(20000, 100000, 1, 0, 0, "", null));
        transactionPoints2.put("two", new TransactionPoint(20000, 300000, 2, 0, 0, "", null));
        transactionPoints2.put("seven", new TransactionPoint(20000, 1400000, 7, 0, 0, "", null));
        transactionPointDao.store("bg", overallPoint2, transactionPoints2);
        // when
        List<TransactionPoint> overallPoints =
                transactionPointDao.readOverallPoints("bg", 0, 100000);
        List<Transaction> transactions =
                transactionPointDao.readTransactions("bg", 0, 100000,
                        TransactionSortColumn.AVERAGE, SortDirection.DESC, 10);
        // then
        assertThat(overallPoints).hasSize(2);
        assertThat(transactions).hasSize(3);
        assertThat(transactions.get(0).getName()).isEqualTo("seven");
        assertThat(transactions.get(0).getTotalMicros()).isEqualTo(2800000);
        assertThat(transactions.get(0).getCount()).isEqualTo(14);
        assertThat(transactions.get(1).getName()).isEqualTo("two");
        assertThat(transactions.get(1).getTotalMicros()).isEqualTo(600000);
        assertThat(transactions.get(1).getCount()).isEqualTo(4);
        assertThat(transactions.get(2).getName()).isEqualTo("one");
        assertThat(transactions.get(2).getTotalMicros()).isEqualTo(200000);
        assertThat(transactions.get(2).getCount()).isEqualTo(2);
    }
}
