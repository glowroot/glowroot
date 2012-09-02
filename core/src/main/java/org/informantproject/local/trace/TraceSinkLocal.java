/**
 * Copyright 2011-2012 the original author or authors.
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

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.core.config.ConfigService;
import org.informantproject.core.config.CoreConfig;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceSink;
import org.informantproject.core.util.DaemonExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Implementation of TraceSink for local storage in embedded H2 database. Some day there may be
 * another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceSinkLocal implements TraceSink {

    private static final Logger logger = LoggerFactory.getLogger(TraceSinkLocal.class);

    private final ExecutorService executorService = DaemonExecutors
            .newSingleThreadExecutor("Informant-StackCollector");

    private final ConfigService configService;
    private final TraceSnapshotService traceSnapshotService;
    private final TraceSnapshotDao traceSnapshotDao;
    private final Ticker ticker;
    private final AtomicInteger queueLength = new AtomicInteger(0);

    @Inject
    TraceSinkLocal(ConfigService configService, TraceSnapshotService traceSnapshotService,
            TraceSnapshotDao traceSnapshotDao, Ticker ticker) {

        this.configService = configService;
        this.traceSnapshotService = traceSnapshotService;
        this.traceSnapshotDao = traceSnapshotDao;
        this.ticker = ticker;
    }

    public void onCompletedTrace(final Trace trace) {
        if (shouldPersist(trace)) {
            queueLength.incrementAndGet();
            executorService.execute(new Runnable() {
                public void run() {
                    try {
                        traceSnapshotDao.storeSnapshot(traceSnapshotService.from(trace,
                                Long.MAX_VALUE));
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                    queueLength.decrementAndGet();
                }
            });
        }
    }

    public void onStuckTrace(Trace trace) {
        try {
            TraceSnapshot snaphsot = traceSnapshotService.from(trace, ticker.read());
            if (!trace.isCompleted()) {
                traceSnapshotDao.storeSnapshot(snaphsot);
            }
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public int getQueueLength() {
        return queueLength.get();
    }

    public void close() {
        logger.debug("close()");
        executorService.shutdownNow();
    }

    private boolean shouldPersist(Trace trace) {
        if (trace.isStuck() || trace.isError()) {
            return true;
        }
        if (trace.isFine()) {
            int finePersistenceThresholdMillis = configService.getFineProfilingConfig()
                    .getPersistenceThresholdMillis();
            if (finePersistenceThresholdMillis == CoreConfig.PERSISTENCE_THRESHOLD_DISABLED) {
                // fall back to core persistence threshold
                return persistBasedOnCorePersistenceThreshold(trace);
            } else {
                return trace.getDuration() >= TimeUnit.MILLISECONDS
                        .toNanos(finePersistenceThresholdMillis);
            }
        } else {
            return persistBasedOnCorePersistenceThreshold(trace);
        }
    }

    private boolean persistBasedOnCorePersistenceThreshold(Trace trace) {
        int persistenceThresholdMillis = configService.getCoreConfig()
                .getPersistenceThresholdMillis();
        return persistenceThresholdMillis != CoreConfig.PERSISTENCE_THRESHOLD_DISABLED
                && trace.getDuration() >= TimeUnit.MILLISECONDS.toNanos(persistenceThresholdMillis);
    }
}
