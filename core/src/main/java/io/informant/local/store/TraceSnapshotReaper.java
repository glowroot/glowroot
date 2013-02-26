/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.store;

import io.informant.config.ConfigService;
import io.informant.config.GeneralConfig;
import io.informant.util.Clock;
import io.informant.util.DaemonExecutors;
import io.informant.util.Singleton;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TraceSnapshotReaper implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(TraceSnapshotReaper.class);
    private static final int CHECK_INTERVAL_MINUTES = 10;
    private static final long MILLISECONDS_PER_HOUR = 60L * 60L * 1000L;

    private final ScheduledExecutorService scheduledExecutor = DaemonExecutors
            .newSingleThreadScheduledExecutor("Informant-TraceSnapshotReaper");

    private final ConfigService configService;
    private final TraceSnapshotDao traceSnapshotDao;
    private final Clock clock;

    TraceSnapshotReaper(ConfigService configService, TraceSnapshotDao traceSnapshotDao,
            Clock clock) {
        this.configService = configService;
        this.traceSnapshotDao = traceSnapshotDao;
        this.clock = clock;
        scheduledExecutor.scheduleAtFixedRate(this, 0, CHECK_INTERVAL_MINUTES * 60,
                TimeUnit.SECONDS);
    }

    public void run() {
        try {
            runInternal();
        } catch (Throwable t) {
            // log and terminate successfully
            logger.error(t.getMessage(), t);
        }
    }

    void close() {
        logger.debug("close()");
        scheduledExecutor.shutdownNow();
    }

    private void runInternal() {
        int snapshotExpirationHours = configService.getGeneralConfig().getSnapshotExpirationHours();
        if (snapshotExpirationHours != GeneralConfig.SNAPSHOT_EXPIRATION_DISABLED) {
            traceSnapshotDao.deleteSnapshotsBefore(clock.currentTimeMillis()
                    - snapshotExpirationHours * MILLISECONDS_PER_HOUR);
        }
    }
}
