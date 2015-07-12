/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.local.ui;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.collector.Aggregate;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDaoTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TransactionCommonServiceTest {

    private AggregateDaoTest aggregateDaoTest;
    private AggregateDao aggregateDao;

    @Before
    public void beforeEachTest() throws Exception {
        aggregateDaoTest = new AggregateDaoTest();
        aggregateDaoTest.beforeEachTest();
        aggregateDaoTest.populateAggregates();
        aggregateDao = aggregateDaoTest.getAggregateDao();
    }

    @After
    public void afterEachTest() throws Exception {
        aggregateDaoTest.afterEachTest();
    }

    @Test
    public void test() throws Exception {
        // given
        ConfigService configService = mock(ConfigService.class);
        when(configService.getAdvancedConfig()).thenReturn(AdvancedConfig.builder().build());
        TransactionCommonService transactionCommonService =
                new TransactionCommonService(aggregateDao, null, configService, 15, 900000);
        // when
        List<Aggregate> aggregates =
                transactionCommonService.getAggregates("a type", null, 0, 3600001, Long.MAX_VALUE);
        // then
        assertThat(aggregates).hasSize(2);
        Aggregate aggregate1 = aggregates.get(0);
        Aggregate aggregate2 = aggregates.get(1);
        assertThat(aggregate1.transactionType()).isEqualTo("a type");
        assertThat(aggregate1.captureTime()).isEqualTo(15000);
        assertThat(aggregate2.transactionType()).isEqualTo("a type");
        assertThat(aggregate2.captureTime()).isEqualTo(30000);
    }
}
