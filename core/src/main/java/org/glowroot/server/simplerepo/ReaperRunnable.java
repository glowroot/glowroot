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
package org.glowroot.server.simplerepo;

import java.sql.SQLException;

import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ScheduledRunnable;
import org.glowroot.server.repo.ConfigRepository;
import org.glowroot.server.repo.config.StorageConfig;

import static java.util.concurrent.TimeUnit.HOURS;

class ReaperRunnable extends ScheduledRunnable {

    private final ConfigRepository configRepository;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final Clock clock;

    ReaperRunnable(ConfigRepository configService, AggregateDao aggregateDao, TraceDao traceDao,
            GaugeValueDao gaugeValueDao, Clock clock) {
        this.configRepository = configService;
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        this.gaugeValueDao = gaugeValueDao;
        this.clock = clock;
    }

    @Override
    protected void runInternal() throws SQLException {

        // FIXME for each serverId
        final long serverId = 0;

        StorageConfig storageConfig = configRepository.getStorageConfig();
        long currentTime = clock.currentTimeMillis();
        for (int i = 0; i < storageConfig.rollupExpirationHours().size(); i++) {
            int hours = storageConfig.rollupExpirationHours().get(i);
            long captureTime = currentTime - HOURS.toMillis(hours);
            aggregateDao.deleteBefore(serverId, captureTime, i);
            if (i == 0) {
                gaugeValueDao.deleteBefore(serverId, captureTime, i);
            }
            gaugeValueDao.deleteBefore(serverId, captureTime, i + 1);
        }
        long traceCaptureTime = currentTime - HOURS.toMillis(storageConfig.traceExpirationHours());
        traceDao.deleteBefore(serverId, traceCaptureTime);
    }
}
