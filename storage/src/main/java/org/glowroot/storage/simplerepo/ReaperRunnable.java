/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.storage.simplerepo;

import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ScheduledRunnable;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ServerRepository.ServerRollup;
import org.glowroot.storage.repo.config.StorageConfig;

import static java.util.concurrent.TimeUnit.HOURS;

class ReaperRunnable extends ScheduledRunnable {

    private final ConfigRepository configRepository;
    private final ServerDao serverDao;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final GaugeMetaDao gaugeMetaDao;
    private final TransactionTypeDao transactionTypeDao;
    private final Clock clock;

    ReaperRunnable(ConfigRepository configService, ServerDao serverDao, AggregateDao aggregateDao,
            TraceDao traceDao, GaugeValueDao gaugeValueDao, GaugeMetaDao gaugeMetaDao,
            TransactionTypeDao transactionTypeDao, Clock clock) {
        this.configRepository = configService;
        this.serverDao = serverDao;
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        this.gaugeValueDao = gaugeValueDao;
        this.gaugeMetaDao = gaugeMetaDao;
        this.transactionTypeDao = transactionTypeDao;
        this.clock = clock;
    }

    @Override
    protected void runInternal() throws Exception {

        long minCaptureTime = Long.MAX_VALUE;
        long currentTime = clock.currentTimeMillis();
        StorageConfig storageConfig = configRepository.getStorageConfig();
        for (int i = 0; i < storageConfig.rollupExpirationHours().size(); i++) {
            int hours = storageConfig.rollupExpirationHours().get(i);
            long captureTime = currentTime - HOURS.toMillis(hours);
            minCaptureTime = Math.min(minCaptureTime, captureTime);
        }
        long traceCaptureTime = currentTime - HOURS.toMillis(storageConfig.traceExpirationHours());
        minCaptureTime = Math.min(minCaptureTime, traceCaptureTime);

        for (ServerRollup serverRollup : serverDao.readServerRollups()) {
            for (int i = 0; i < storageConfig.rollupExpirationHours().size(); i++) {
                int hours = storageConfig.rollupExpirationHours().get(i);
                long captureTime = currentTime - HOURS.toMillis(hours);
                aggregateDao.deleteBefore(serverRollup.name(), captureTime, i);
                if (i == 0) {
                    gaugeValueDao.deleteBefore(serverRollup.name(), captureTime, i);
                }
                gaugeValueDao.deleteBefore(serverRollup.name(), captureTime, i + 1);
            }
            gaugeMetaDao.deleteBefore(serverRollup.name(), minCaptureTime);
            transactionTypeDao.deleteBefore(serverRollup.name(), minCaptureTime);
            if (serverRollup.leaf()) {
                traceDao.deleteBefore(serverRollup.name(), traceCaptureTime);
                minCaptureTime = Math.min(minCaptureTime, traceCaptureTime);
            }
        }
    }
}
