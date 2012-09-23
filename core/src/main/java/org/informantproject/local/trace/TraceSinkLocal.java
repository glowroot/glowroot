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
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceSink;
import org.informantproject.core.util.DaemonExecutors;

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

    private static final int PENDING_LIMIT = 100;

    private final ExecutorService executorService = DaemonExecutors
            .newSingleThreadExecutor("Informant-TraceSink");

    private final TraceSnapshotService traceSnapshotService;
    private final TraceSnapshotDao traceSnapshotDao;
    private final Ticker ticker;
    private final Set<Trace> pendingCompleteTraces = new CopyOnWriteArraySet<Trace>();

    private volatile long lastWarningTick;
    private volatile int countSinceLastWarning;

    @Inject
    TraceSinkLocal(TraceSnapshotService traceSnapshotService, TraceSnapshotDao traceSnapshotDao,
            Ticker ticker) {

        this.traceSnapshotService = traceSnapshotService;
        this.traceSnapshotDao = traceSnapshotDao;
        this.ticker = ticker;
        lastWarningTick = ticker.read() - TimeUnit.SECONDS.toNanos(60);
    }

    public void onCompletedTrace(final Trace trace) {
        if (traceSnapshotService.shouldPersist(trace)) {
            if (pendingCompleteTraces.size() >= PENDING_LIMIT) {
                logPendingLimitWarning();
            }
            pendingCompleteTraces.add(trace);
            executorService.execute(new Runnable() {
                public void run() {
                    try {
                        traceSnapshotDao.storeSnapshot(traceSnapshotService.from(trace,
                                Long.MAX_VALUE));
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                    pendingCompleteTraces.remove(trace);
                }
            });
        }
    }

    // synchronized to prevent two threads from logging the warning at the same time (one should
    // log it and the other should increment the count for the next log)
    private synchronized void logPendingLimitWarning() {
        long tick = ticker.read();
        if (TimeUnit.NANOSECONDS.toSeconds(tick - lastWarningTick) >= 60) {
            logger.warn("not storing a trace in the local h2 database because of an excessive"
                    + " backlog of {} traces already waiting to be stored (this warning will"
                    + " appear at most once a minute, there were {} additional traces not stored"
                    + " in the local h2 database since the last warning)", PENDING_LIMIT,
                    countSinceLastWarning);
            lastWarningTick = tick;
            countSinceLastWarning = 0;
        } else {
            countSinceLastWarning++;
        }
    }

    // no need to throttle stuck trace storage since throttling is handled upstream by using a
    // single thread executor in StuckTraceCollector
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

    public Collection<Trace> getPendingCompleteTraces() {
        return pendingCompleteTraces;
    }

    public int getPendingCount() {
        // TODO need to include
        return pendingCompleteTraces.size();
    }

    public void close() {
        logger.debug("close()");
        executorService.shutdownNow();
    }
}
