/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.local.trace;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.trace.Trace;
import io.informant.core.trace.TraceSink;
import io.informant.core.util.DaemonExecutors;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import checkers.lock.quals.GuardedBy;

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
    private final Set<Trace> pendingCompleteTraces = Sets.newCopyOnWriteArraySet();

    private final Object warningLock = new Object();
    @GuardedBy("warningLock")
    private final Stopwatch lastWarningStopwatch;
    @GuardedBy("warningLock")
    private int countSinceLastWarning;

    @Inject
    TraceSinkLocal(TraceSnapshotService traceSnapshotService, TraceSnapshotDao traceSnapshotDao,
            Ticker ticker) {
        this.traceSnapshotService = traceSnapshotService;
        this.traceSnapshotDao = traceSnapshotDao;
        this.ticker = ticker;
        lastWarningStopwatch = new Stopwatch(ticker);
    }

    public void onCompletedTrace(final Trace trace) {
        if (traceSnapshotService.shouldStore(trace)) {
            // promote thread local trace metrics since they will be reset after this method returns
            trace.promoteTraceMetrics();
            if (pendingCompleteTraces.size() >= PENDING_LIMIT) {
                logPendingLimitWarning();
            }
            pendingCompleteTraces.add(trace);
            executorService.execute(new Runnable() {
                public void run() {
                    try {
                        traceSnapshotDao.storeSnapshot(TraceWriter.toTraceSnapshot(trace,
                                Long.MAX_VALUE, true));
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                    pendingCompleteTraces.remove(trace);
                }
            });
        }
    }

    private void logPendingLimitWarning() {
        // synchronized to prevent two threads from logging the warning at the same time (one should
        // log it and the other should increment the count for the next log)
        synchronized (warningLock) {
            // lastWarningStopwatch is not running the very first time
            if (!lastWarningStopwatch.isRunning() || lastWarningStopwatch.elapsedMillis() > 60000) {
                logger.warn("not storing a trace in the local h2 database because of an excessive"
                        + " backlog of {} traces already waiting to be stored (this warning will"
                        + " appear at most once a minute, there were {} additional traces not"
                        + " stored in the local h2 database since the last warning)",
                        PENDING_LIMIT,
                        countSinceLastWarning);
                lastWarningStopwatch.reset().start();
                countSinceLastWarning = 0;
            } else {
                countSinceLastWarning++;
            }
        }
    }

    // no need to throttle stuck trace storage since throttling is handled upstream by using a
    // single thread executor in StuckTraceCollector
    public void onStuckTrace(Trace trace) {
        try {
            TraceSnapshot snaphsot = TraceWriter.toTraceSnapshot(trace, ticker.read(), true);
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
