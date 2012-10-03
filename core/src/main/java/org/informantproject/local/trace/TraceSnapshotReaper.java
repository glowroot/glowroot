/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.local.trace;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.config.ConfigService;
import org.informantproject.core.util.Clock;
import org.informantproject.core.util.DaemonExecutors;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceSnapshotReaper implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(TraceSnapshotReaper.class);
    private static final int CHECK_INTERVAL_MINUTES = 10;
    private static final long MILLISECONDS_PER_HOUR = 60L * 60L * 1000L;

    private final ScheduledExecutorService scheduledExecutor = DaemonExecutors
            .newSingleThreadScheduledExecutor("Informant-TraceSnapshotReaper");

    private final ConfigService configService;
    private final TraceSnapshotDao traceSnapshotDao;
    private final Clock clock;

    @Inject
    private TraceSnapshotReaper(ConfigService configService, TraceSnapshotDao traceSnapshotDao,
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

    public void close() {
        logger.debug("close()");
        scheduledExecutor.shutdownNow();
    }

    private void runInternal() {
        long keepMillis = configService.getCoreConfig().getKeepTraceSnapshotHours()
                * MILLISECONDS_PER_HOUR;
        traceSnapshotDao.deleteSnapshotsBefore(clock.currentTimeMillis() - keepMillis);
    }
}
