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
package org.glowroot.agent.embedded.repo;

import java.sql.SQLException;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.ImmutableEmbeddedStorageConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReaperRunnableTest {

    private ConfigRepositoryImpl configRepository;
    private AggregateDao aggregateDao;
    private TraceDao traceDao;
    private GaugeIdDao gaugeIdDao;
    private GaugeNameDao gaugeNameDao;
    private GaugeValueDao gaugeValueDao;
    private TransactionTypeDao transactionTypeDao;
    private FullQueryTextDao fullQueryTextDao;
    private IncidentDao incidentDao;
    private ReaperFailureTracker failureTracker;
    private Clock clock;
    private ReaperRunnable reaperRunnable;

    @BeforeEach
    void beforeEach() {
        configRepository = mock(ConfigRepositoryImpl.class);
        aggregateDao = mock(AggregateDao.class);
        traceDao = mock(TraceDao.class);
        gaugeIdDao = mock(GaugeIdDao.class);
        gaugeNameDao = mock(GaugeNameDao.class);
        gaugeValueDao = mock(GaugeValueDao.class);
        transactionTypeDao = mock(TransactionTypeDao.class);
        fullQueryTextDao = mock(FullQueryTextDao.class);
        incidentDao = mock(IncidentDao.class);
        failureTracker = new ReaperFailureTracker();
        clock = mock(Clock.class);
        when(clock.currentTimeMillis()).thenReturn(1_000_000_000L);
        when(configRepository.getEmbeddedStorageConfig()).thenReturn(
                ImmutableEmbeddedStorageConfig.builder()
                        .rollupExpirationHours(ImmutableList.of(24))
                        .traceExpirationHours(24)
                        .fullQueryTextExpirationHours(24)
                        .build());
        reaperRunnable = new ReaperRunnable(configRepository, aggregateDao, traceDao, gaugeIdDao,
                gaugeNameDao, gaugeValueDao, transactionTypeDao, fullQueryTextDao, incidentDao,
                failureTracker, clock);
    }

    @Test
    void continuesAfterOneTableFails() throws Exception {
        doThrow(new SQLException("corrupt tn")).when(aggregateDao)
                .deleteTransactionNameBefore(anyLong(), eq(0));

        reaperRunnable.runInternal();

        verify(aggregateDao).deleteOverallBefore(anyLong(), eq(0));
        verify(aggregateDao).deleteTransactionNameBefore(anyLong(), eq(0));
        verify(gaugeValueDao).deleteBefore(anyLong(), eq(0));
        verify(gaugeValueDao).deleteBefore(anyLong(), eq(1));
        verify(traceDao).deleteAttributeNamesBefore(anyLong());
        verify(traceDao).deleteTracesBefore(anyLong());
        verify(traceDao).deleteTraceAttributesBefore(anyLong());
        verify(fullQueryTextDao).deleteBefore(anyLong());
        verify(incidentDao).deleteResolvedIncidentsBefore(anyLong());
        assertThat(failureTracker.getConsecutiveFailedCycles()).isEqualTo(1);
        assertThat(failureTracker.snapshot().lastFailedSteps()).containsExactly("aggregate_tn_rollup_0");
    }

    @Test
    void continuesAfterTraceTableFails() throws Exception {
        doThrow(new SQLException("corrupt trace")).when(traceDao).deleteTracesBefore(anyLong());

        reaperRunnable.runInternal();

        verify(traceDao).deleteAttributeNamesBefore(anyLong());
        verify(traceDao).deleteTracesBefore(anyLong());
        verify(traceDao).deleteTraceAttributesBefore(anyLong());
        verify(fullQueryTextDao).deleteBefore(anyLong());
        assertThat(failureTracker.snapshot().lastFailedSteps()).containsExactly("trace");
    }

    @Test
    void resetsStreakWhenCycleSucceeds() throws Exception {
        doThrow(new SQLException("corrupt tn")).when(aggregateDao)
                .deleteTransactionNameBefore(anyLong(), anyInt());
        reaperRunnable.runInternal();
        assertThat(failureTracker.getConsecutiveFailedCycles()).isEqualTo(1);

        // clear stub so next cycle succeeds
        org.mockito.Mockito.reset(aggregateDao);
        reaperRunnable.runInternal();
        assertThat(failureTracker.getConsecutiveFailedCycles()).isEqualTo(0);
    }

    @Test
    void recordsFailureWhenSetupThrowsOutsideStep() throws Exception {
        when(configRepository.getEmbeddedStorageConfig())
                .thenThrow(new RuntimeException("config boom"));

        reaperRunnable.runInternal();

        assertThat(failureTracker.getConsecutiveFailedCycles()).isEqualTo(1);
        assertThat(failureTracker.snapshot().lastFailedSteps()).containsExactly("reaper_cycle");
    }

    @Test
    void interruptDoesNotUpdateFailureStreak() throws Exception {
        // IncidentDao declares throws Exception, so InterruptedException is a valid stub.
        doThrow(new InterruptedException("shutdown")).when(incidentDao)
                .deleteResolvedIncidentsBefore(anyLong());

        assertThatThrownBy(() -> reaperRunnable.runInternal())
                .isInstanceOf(InterruptedException.class);
        assertThat(Thread.interrupted()).isTrue();
        assertThat(failureTracker.getConsecutiveFailedCycles()).isEqualTo(0);
    }

    @Test
    void errorFromStepRecordsFailureNotSuccess() throws Exception {
        doThrow(new OutOfMemoryError("oom")).when(incidentDao)
                .deleteResolvedIncidentsBefore(anyLong());

        assertThatThrownBy(() -> reaperRunnable.runInternal())
                .isInstanceOf(OutOfMemoryError.class);
        assertThat(failureTracker.getConsecutiveFailedCycles()).isEqualTo(1);
        assertThat(failureTracker.snapshot().lastFailedSteps()).containsExactly("incident");
    }

    @Test
    void errorAfterPriorStepFailureStillRecordsFailure() throws Exception {
        doThrow(new SQLException("corrupt tn")).when(aggregateDao)
                .deleteTransactionNameBefore(anyLong(), eq(0));
        doThrow(new OutOfMemoryError("oom")).when(incidentDao)
                .deleteResolvedIncidentsBefore(anyLong());

        assertThatThrownBy(() -> reaperRunnable.runInternal())
                .isInstanceOf(OutOfMemoryError.class);
        assertThat(failureTracker.getConsecutiveFailedCycles()).isEqualTo(1);
        assertThat(failureTracker.snapshot().lastFailedSteps())
                .contains("aggregate_tn_rollup_0", "incident");
        // First failure wins the stored message (SQLException), not the later OOM.
        assertThat(failureTracker.snapshot().lastErrorMessage()).contains("corrupt tn");
    }

    @Test
    void errorOutsideRunStepRecordsReaperCycle() {
        when(configRepository.getEmbeddedStorageConfig())
                .thenThrow(new OutOfMemoryError("oom-config"));

        assertThatThrownBy(() -> reaperRunnable.runInternal())
                .isInstanceOf(OutOfMemoryError.class);
        assertThat(failureTracker.getConsecutiveFailedCycles()).isEqualTo(1);
        assertThat(failureTracker.snapshot().lastFailedSteps()).containsExactly("reaper_cycle");
    }

    @Test
    void interruptAfterPriorStepFailureDoesNotUpdateStreak() throws Exception {
        doThrow(new SQLException("corrupt tn")).when(aggregateDao)
                .deleteTransactionNameBefore(anyLong(), eq(0));
        doThrow(new InterruptedException("shutdown")).when(incidentDao)
                .deleteResolvedIncidentsBefore(anyLong());

        assertThatThrownBy(() -> reaperRunnable.runInternal())
                .isInstanceOf(InterruptedException.class);
        assertThat(Thread.interrupted()).isTrue();
        // Mid-cycle interrupt skips tracker update (including earlier step failures).
        assertThat(failureTracker.getConsecutiveFailedCycles()).isEqualTo(0);
    }

    @Test
    void errorIncrementsExistingStreak() throws Exception {
        doThrow(new SQLException("corrupt tn")).when(aggregateDao)
                .deleteTransactionNameBefore(anyLong(), eq(0));
        reaperRunnable.runInternal();
        assertThat(failureTracker.getConsecutiveFailedCycles()).isEqualTo(1);

        org.mockito.Mockito.reset(aggregateDao);
        doThrow(new OutOfMemoryError("oom")).when(incidentDao)
                .deleteResolvedIncidentsBefore(anyLong());
        assertThatThrownBy(() -> reaperRunnable.runInternal())
                .isInstanceOf(OutOfMemoryError.class);
        assertThat(failureTracker.getConsecutiveFailedCycles()).isEqualTo(2);
    }
}
