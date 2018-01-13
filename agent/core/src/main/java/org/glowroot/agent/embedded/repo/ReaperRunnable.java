/*
 * Copyright 2012-2018 the original author or authors.
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

import org.glowroot.common.config.EmbeddedStorageConfig;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ScheduledRunnable;

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
    private final Clock clock;

    ReaperRunnable(ConfigRepositoryImpl configService, AggregateDao aggregateDao, TraceDao traceDao,
            GaugeIdDao gaugeIdDao, GaugeNameDao gaugeNameDao, GaugeValueDao gaugeValueDao,
            TransactionTypeDao transactionTypeDao, FullQueryTextDao fullQueryTextDao,
            IncidentDao incidentDao, Clock clock) {
        this.configRepository = configService;
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        this.gaugeIdDao = gaugeIdDao;
        this.gaugeNameDao = gaugeNameDao;
        this.gaugeValueDao = gaugeValueDao;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;
        this.incidentDao = incidentDao;
        this.clock = clock;
    }

    @Override
    protected void runInternal() throws Exception {
        long minCaptureTime = Long.MAX_VALUE;
        long currentTime = clock.currentTimeMillis();
        EmbeddedStorageConfig storageConfig = configRepository.getEmbeddedStorageConfig();
        for (int i = 0; i < storageConfig.rollupExpirationHours().size(); i++) {
            int expirationHours = storageConfig.rollupExpirationHours().get(i);
            if (expirationHours == 0) {
                // zero value expiration means never expire
                minCaptureTime = 0;
                continue;
            }
            long captureTime = currentTime - HOURS.toMillis(expirationHours);
            aggregateDao.deleteBefore(captureTime, i);
            if (i == 0) {
                gaugeValueDao.deleteBefore(captureTime, i);
            }
            gaugeValueDao.deleteBefore(captureTime, i + 1);
            minCaptureTime = Math.min(minCaptureTime, captureTime);
        }
        if (minCaptureTime != 0) {
            gaugeIdDao.deleteBefore(minCaptureTime);
            gaugeNameDao.deleteBefore(minCaptureTime);
        }
        int traceExpirationHours = storageConfig.traceExpirationHours();
        if (traceExpirationHours != 0) {
            long traceCaptureTime = currentTime - HOURS.toMillis(traceExpirationHours);
            traceDao.deleteBefore(traceCaptureTime);
            minCaptureTime = Math.min(minCaptureTime, traceCaptureTime);
        }
        if (minCaptureTime != 0) {
            transactionTypeDao.deleteBefore(minCaptureTime);
        }
        int fullQueryTextExpirationHours = storageConfig.fullQueryTextExpirationHours();
        if (fullQueryTextExpirationHours != 0) {
            fullQueryTextDao
                    .deleteBefore(currentTime - HOURS.toMillis(fullQueryTextExpirationHours));
        }
        incidentDao.deleteResolvedIncidentsBefore(
                currentTime - HOURS.toMillis(StorageConfig.RESOLVED_INCIDENT_EXPIRATION_HOURS));
    }
}
