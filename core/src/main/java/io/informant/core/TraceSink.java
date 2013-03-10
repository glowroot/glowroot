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
package io.informant.core;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import io.informant.config.ConfigService;
import io.informant.config.GeneralConfig;
import io.informant.core.snapshot.Snapshot;
import io.informant.core.snapshot.SnapshotCreator;
import io.informant.core.trace.Trace;
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
public class TraceSink {

    private static final Logger logger = LoggerFactory.getLogger(TraceSink.class);

    private static final int PENDING_LIMIT = 100;

    private final ExecutorService executorService;
    private final ConfigService configService;
    private final SnapshotSink snapshotSink;
    private final Ticker ticker;
    private final Set<Trace> pendingCompleteTraces = Sets.newCopyOnWriteArraySet();

    private final RateLimiter warningRateLimiter = RateLimiter.create(1.0 / 60);
    @GuardedBy("warningLock")
    private int countSinceLastWarning;

    TraceSink(ExecutorService executorService, ConfigService configService,
            SnapshotSink snapshotSink, Ticker ticker) {
        this.executorService = executorService;
        this.configService = configService;
        this.snapshotSink = snapshotSink;
        this.ticker = ticker;
    }

    public void onCompletedTrace(final Trace trace) {
        if (shouldStore(trace)) {
            // promote thread local trace metrics since they will be reset after this method returns
            // TODO instead of confusing "promotion", just capture the metric snapshots here and
            // pass them to the trace snapshot creation below
            trace.promoteMetrics();
            if (pendingCompleteTraces.size() >= PENDING_LIMIT) {
                logPendingLimitWarning();
                return;
            }
            pendingCompleteTraces.add(trace);
            executorService.execute(new Runnable() {
                public void run() {
                    try {
                        Snapshot snapshot = SnapshotCreator.createSnapshot(trace, Long.MAX_VALUE,
                                false);
                        snapshotSink.store(snapshot);
                        pendingCompleteTraces.remove(trace);
                    } catch (Throwable t) {
                        // log and terminate successfully
                        logger.error(t.getMessage(), t);
                    }
                }
            });
        }
    }

    private void logPendingLimitWarning() {
        synchronized (warningRateLimiter) {
            if (warningRateLimiter.tryAcquire(0, MILLISECONDS)) {
                logger.warn("not storing a trace because of an excessive backlog of {} traces"
                        + " already waiting to be stored (this warning will appear at most once a"
                        + " minute, there were {} additional traces not stored since the last"
                        + " warning)", PENDING_LIMIT, countSinceLastWarning);
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
            Snapshot snaphsot = SnapshotCreator.createSnapshot(trace, ticker.read(), false);
            if (!trace.isCompleted()) {
                snapshotSink.store(snaphsot);
            }
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public boolean shouldStore(Trace trace) {
        if (trace.isStuck() || trace.isError()) {
            return true;
        }
        long duration = trace.getDuration();
        // check if should store for user tracing
        String userId = trace.getUserId();
        if (userId != null && userId.equals(configService.getUserConfig().getUserId())
                && duration >= MILLISECONDS.toNanos(configService.getUserConfig()
                        .getStoreThresholdMillis())) {
            return true;
        }
        // check if should store for fine profiling
        if (trace.isFine()) {
            int fineStoreThresholdMillis = configService.getFineProfilingConfig()
                    .getStoreThresholdMillis();
            if (fineStoreThresholdMillis != GeneralConfig.STORE_THRESHOLD_DISABLED) {
                return trace.getDuration() >= MILLISECONDS.toNanos(fineStoreThresholdMillis);
            }
        }
        // fall back to core store threshold
        return shouldStoreBasedOnCoreStoreThreshold(trace);
    }

    public Collection<Trace> getPendingCompleteTraces() {
        return pendingCompleteTraces;
    }

    private boolean shouldStoreBasedOnCoreStoreThreshold(Trace trace) {
        int storeThresholdMillis = configService.getGeneralConfig().getStoreThresholdMillis();
        return storeThresholdMillis != GeneralConfig.STORE_THRESHOLD_DISABLED
                && trace.getDuration() >= MILLISECONDS.toNanos(storeThresholdMillis);
    }
}
