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
package io.informant.local.store;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import io.informant.core.Trace;
import io.informant.core.TraceSink;
import io.informant.util.DaemonExecutors;
import io.informant.util.Singleton;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.lock.quals.GuardedBy;

import com.google.common.base.Ticker;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.RateLimiter;

/**
 * Implementation of TraceSink for local storage in embedded H2 database. Some day there may be
 * another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class LocalTraceSink implements TraceSink {

    private static final Logger logger = LoggerFactory.getLogger(LocalTraceSink.class);

    private static final int PENDING_LIMIT = 100;

    private final ExecutorService executorService = DaemonExecutors
            .newSingleThreadExecutor("Informant-TraceSink");

    private final TraceSnapshotService traceSnapshotService;
    private final TraceSnapshotDao traceSnapshotDao;
    private final Ticker ticker;
    private final Set<Trace> pendingCompleteTraces = Sets.newCopyOnWriteArraySet();

    private final RateLimiter warningRateLimiter = RateLimiter.create(1.0 / 60);
    @GuardedBy("warningLock")
    private int countSinceLastWarning;

    LocalTraceSink(TraceSnapshotService traceSnapshotService,
            TraceSnapshotDao traceSnapshotDao, Ticker ticker) {
        this.traceSnapshotService = traceSnapshotService;
        this.traceSnapshotDao = traceSnapshotDao;
        this.ticker = ticker;
    }

    public void onCompletedTrace(final Trace trace) {
        if (traceSnapshotService.shouldStore(trace)) {
            // promote thread local trace metrics since they will be reset after this method returns
            trace.promoteTraceMetrics();
            if (pendingCompleteTraces.size() >= PENDING_LIMIT) {
                logPendingLimitWarning();
                return;
            }
            pendingCompleteTraces.add(trace);
            executorService.execute(new Runnable() {
                public void run() {
                    try {
                        traceSnapshotDao.storeSnapshot(TraceWriter.toTraceSnapshot(trace,
                                Long.MAX_VALUE, false));
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                    pendingCompleteTraces.remove(trace);
                }
            });
        }
    }

    private void logPendingLimitWarning() {
        synchronized (warningRateLimiter) {
            if (warningRateLimiter.tryAcquire(0, MILLISECONDS)) {
                logger.warn("not storing a trace in the local h2 database because of an excessive"
                        + " backlog of {} traces already waiting to be stored (this warning will"
                        + " appear at most once a minute, there were {} additional traces not"
                        + " stored in the local h2 database since the last warning)",
                        PENDING_LIMIT, countSinceLastWarning);
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
            TraceSnapshot snaphsot = TraceWriter.toTraceSnapshot(trace, ticker.read(), false);
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

    void close() {
        logger.debug("close()");
        executorService.shutdownNow();
    }
}
