/*
 * Copyright 2012-2026 the original author or authors.
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

import java.util.ArrayList;
import java.util.List;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.common.Constants;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ScheduledRunnable;
import org.glowroot.common2.config.EmbeddedStorageConfig;

import static java.util.concurrent.TimeUnit.HOURS;

class ReaperRunnable extends ScheduledRunnable {

    private final ConfigRepositoryImpl configRepository;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeIdDao gaugeIdDao;
    private final GaugeNameDao gaugeNameDao;
    private final GaugeValueDao gaugeValueDao;
    private final TransactionTypeDao transactionTypeDao;
    private final FullQueryTextDao fullQueryTextDao;
    private final IncidentDao incidentDao;
    private final ReaperFailureTracker failureTracker;
    private final Clock clock;

    ReaperRunnable(ConfigRepositoryImpl configService, AggregateDao aggregateDao, TraceDao traceDao,
            GaugeIdDao gaugeIdDao, GaugeNameDao gaugeNameDao, GaugeValueDao gaugeValueDao,
            TransactionTypeDao transactionTypeDao, FullQueryTextDao fullQueryTextDao,
            IncidentDao incidentDao, ReaperFailureTracker failureTracker, Clock clock) {
        this.configRepository = configService;
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        this.gaugeIdDao = gaugeIdDao;
        this.gaugeNameDao = gaugeNameDao;
        this.gaugeValueDao = gaugeValueDao;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;
        this.incidentDao = incidentDao;
        this.failureTracker = failureTracker;
        this.clock = clock;
    }

    @Override
    protected void runInternal() throws Exception {
        List<String> failedSteps = new ArrayList<>();
        @Nullable
        Exception firstException = null;
        long currentTime = clock.currentTimeMillis();
        boolean skipTrackerUpdate = false;
        try {
            long minCaptureTime = Long.MAX_VALUE;
            EmbeddedStorageConfig storageConfig = configRepository.getEmbeddedStorageConfig();
            for (int i = 0; i < storageConfig.rollupExpirationHours().size(); i++) {
                int expirationHours = storageConfig.rollupExpirationHours().get(i);
                if (expirationHours == 0) {
                    // zero value expiration means never expire
                    minCaptureTime = 0;
                    continue;
                }
                final long captureTime = currentTime - HOURS.toMillis(expirationHours);
                final int rollupLevel = i;
                firstException = runStep("aggregate_tt_rollup_" + rollupLevel, failedSteps,
                        firstException,
                        () -> aggregateDao.deleteOverallBefore(captureTime, rollupLevel));
                firstException = runStep("aggregate_tn_rollup_" + rollupLevel, failedSteps,
                        firstException,
                        () -> aggregateDao.deleteTransactionNameBefore(captureTime, rollupLevel));
                if (rollupLevel == 0) {
                    firstException = runStep("gauge_value_rollup_0", failedSteps, firstException,
                            () -> gaugeValueDao.deleteBefore(captureTime, 0));
                }
                final int gaugeLevel = rollupLevel + 1;
                firstException = runStep("gauge_value_rollup_" + gaugeLevel, failedSteps,
                        firstException, () -> gaugeValueDao.deleteBefore(captureTime, gaugeLevel));
                minCaptureTime = Math.min(minCaptureTime, captureTime);
            }
            if (minCaptureTime != 0) {
                final long gaugeMetaCaptureTime = minCaptureTime;
                firstException = runStep("gauge_id", failedSteps, firstException,
                        () -> gaugeIdDao.deleteBefore(gaugeMetaCaptureTime));
                firstException = runStep("gauge_name", failedSteps, firstException,
                        () -> gaugeNameDao.deleteBefore(gaugeMetaCaptureTime));
            }
            int traceExpirationHours = storageConfig.traceExpirationHours();
            if (traceExpirationHours != 0) {
                final long traceCaptureTime = currentTime - HOURS.toMillis(traceExpirationHours);
                firstException = runStep("trace_attribute_name", failedSteps, firstException,
                        () -> traceDao.deleteAttributeNamesBefore(traceCaptureTime));
                firstException = runStep("trace", failedSteps, firstException,
                        () -> traceDao.deleteTracesBefore(traceCaptureTime));
                firstException = runStep("trace_attribute", failedSteps, firstException,
                        () -> traceDao.deleteTraceAttributesBefore(traceCaptureTime));
                minCaptureTime = Math.min(minCaptureTime, traceCaptureTime);
            }
            if (minCaptureTime != 0) {
                final long txnTypeCaptureTime = minCaptureTime;
                firstException = runStep("transaction_types", failedSteps, firstException,
                        () -> transactionTypeDao.deleteBefore(txnTypeCaptureTime));
            }
            int fullQueryTextExpirationHours = storageConfig.fullQueryTextExpirationHours();
            if (fullQueryTextExpirationHours != 0) {
                long fullQueryTextCaptureTime =
                        currentTime - HOURS.toMillis(fullQueryTextExpirationHours);
                firstException = runStep("full_query_text", failedSteps, firstException,
                        () -> fullQueryTextDao.deleteBefore(fullQueryTextCaptureTime));
            }
            long incidentCaptureTime =
                    currentTime - HOURS.toMillis(Constants.RESOLVED_INCIDENT_EXPIRATION_HOURS);
            firstException = runStep("incident", failedSteps, firstException,
                    () -> incidentDao.deleteResolvedIncidentsBefore(incidentCaptureTime));
        } catch (InterruptedException e) {
            // Do not treat shutdown/interrupt as a retention-table failure streak.
            skipTrackerUpdate = true;
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            // Failures outside runStep (e.g. storage config read) still update the streak.
            failedSteps.add("reaper_cycle");
            if (firstException == null) {
                firstException = e;
            }
        } finally {
            if (!skipTrackerUpdate) {
                if (failedSteps.isEmpty()) {
                    failureTracker.recordCycleSuccess();
                } else if (firstException != null) {
                    failureTracker.recordCycleFailure(failedSteps, firstException, currentTime);
                }
            }
        }
    }

    private static @Nullable Exception runStep(String stepName, List<String> failedSteps,
            @Nullable Exception firstException, Step step) throws InterruptedException {
        try {
            step.run();
            return firstException;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            failedSteps.add(stepName);
            return firstException == null ? e : firstException;
        }
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }
}
