/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.common2.repo.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.config.StorageConfig;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.util.RollupLevelService.DataKind;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RollupLevelServiceTest {

    private static final long NOW = 1_700_000_000_000L;

    private ConfigRepository configRepository;
    private Clock clock;
    private RollupLevelService rollupLevelService;

    @BeforeEach
    public void beforeEachTest() {
        configRepository = mock(ConfigRepository.class);
        clock = mock(Clock.class);
        when(clock.currentTimeMillis()).thenReturn(NOW);
        when(configRepository.getRollupConfigs()).thenReturn(RollupConfig.buildRollupConfigs());
        StorageConfig storageConfig = ImmutableCentralStorageConfig.builder().build();
        when(configRepository.getStorageConfig()).thenReturn(storageConfig);
        rollupLevelService = new RollupLevelService(configRepository, clock);
    }

    @Test
    public void floorAndCeilRollupTime() {
        assertThat(RollupLevelService.getFloorRollupTime(65_000, 60_000)).isEqualTo(60_000);
        assertThat(RollupLevelService.getCeilRollupTime(60_000, 60_000)).isEqualTo(60_000);
        assertThat(RollupLevelService.getCeilRollupTime(60_001, 60_000)).isEqualTo(120_000);
        assertThat(RollupLevelService.getSafeRollupTime(65_000, 60_000)).isEqualTo(60_000);
    }

    @Test
    public void viewLevelUsesFinestWhenWindowBelowNextThreshold() {
        long from = NOW - MINUTES.toMillis(10);
        long to = NOW;
        assertThat(rollupLevelService.getRollupLevelForView(from, to, DataKind.GENERAL))
                .isEqualTo(0);
    }

    @Test
    public void viewLevelStepsUpForWiderWindows() {
        long from = NOW - HOURS.toMillis(2);
        long to = NOW;
        assertThat(rollupLevelService.getRollupLevelForView(from, to, DataKind.GENERAL))
                .isEqualTo(1);
    }

    @Test
    public void viewLevelUsesCoarsestWhenDataExpiredAtFinerLevels() {
        // coarser levels keep data longer; far past forces highest index
        long from = NOW - DAYS.toMillis(400);
        long to = from + HOURS.toMillis(1);
        assertThat(rollupLevelService.getRollupLevelForView(from, to, DataKind.GENERAL))
                .isEqualTo(3);
    }

    @Test
    public void reportLevelIgnoresWindowWidth() throws Exception {
        long from = NOW - MINUTES.toMillis(5);
        assertThat(rollupLevelService.getRollupLevelForReport(from, DataKind.GENERAL))
                .isEqualTo(0);
        assertThat(rollupLevelService.getRollupLevelForReport(from, DataKind.QUERY))
                .isEqualTo(0);
        assertThat(rollupLevelService.getRollupLevelForReport(from, DataKind.PROFILE))
                .isEqualTo(0);
    }

    @Test
    public void gaugeViewLevelZeroOnlyForLeafAgents() {
        long from = NOW - MINUTES.toMillis(5);
        long to = NOW;
        assertThat(rollupLevelService.getGaugeRollupLevelForView(from, to, false)).isEqualTo(0);
        assertThat(rollupLevelService.getGaugeRollupLevelForView(from, to, true)).isEqualTo(1);
    }

    @Test
    public void gaugeReportLevelRecentIsZero() throws Exception {
        assertThat(rollupLevelService.getGaugeRollupLevelForReport(NOW - MINUTES.toMillis(30)))
                .isEqualTo(0);
    }
}
