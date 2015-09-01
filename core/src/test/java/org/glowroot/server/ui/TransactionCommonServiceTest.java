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
package org.glowroot.server.ui;

import java.util.List;

import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.live.LiveAggregateRepository;
import org.glowroot.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.server.repo.AggregateRepository;
import org.glowroot.server.repo.ConfigRepository;
import org.glowroot.server.repo.ConfigRepository.RollupConfig;
import org.glowroot.server.repo.ImmutableRollupConfig;
import org.glowroot.server.repo.config.ImmutableStorageConfig;
import org.glowroot.server.simplerepo.AggregateDaoTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TransactionCommonServiceTest {

    private AggregateDaoTest aggregateDaoTest;
    private AggregateRepository aggregateRepository;

    @Before
    public void beforeEachTest() throws Exception {
        aggregateDaoTest = new AggregateDaoTest();
        aggregateDaoTest.beforeEachTest();
        aggregateDaoTest.populateAggregates();
        aggregateRepository = aggregateDaoTest.getAggregateRepository();
    }

    @After
    public void afterEachTest() throws Exception {
        aggregateDaoTest.afterEachTest();
    }

    @Test
    public void test() throws Exception {
        // given
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
        LiveAggregateRepository liveAggregateRepository = mock(LiveAggregateRepository.class);
        TransactionCommonService transactionCommonService = new TransactionCommonService(
                aggregateRepository, liveAggregateRepository, configRepository);
        // when
        List<OverviewAggregate> aggregates = transactionCommonService
                .getOverviewAggregates("a type", null, 0, 3600001, Long.MAX_VALUE);
        // then
        assertThat(aggregates).hasSize(2);
        OverviewAggregate aggregate1 = aggregates.get(0);
        OverviewAggregate aggregate2 = aggregates.get(1);
        assertThat(aggregate1.captureTime()).isEqualTo(15000);
        assertThat(aggregate2.captureTime()).isEqualTo(30000);
    }
}
