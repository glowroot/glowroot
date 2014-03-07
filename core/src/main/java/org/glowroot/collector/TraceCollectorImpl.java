/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.collector;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import checkers.lock.quals.GuardedBy;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Ticker;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.config.ConfigService;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.TraceCollector;
import org.glowroot.trace.model.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceCollectorImpl implements TraceCollector {

    private static final Logger logger = LoggerFactory.getLogger(TraceCollectorImpl.class);

    private static final int PENDING_LIMIT = 100;

    private final ExecutorService executorService;
    private final ConfigService configService;
    private final SnapshotRepository snapshotRepository;
    @Nullable
    private final Aggregator aggregator;
    private final Clock clock;
    private final Ticker ticker;
    private final Set<Trace> pendingCompleteTraces = Sets.newCopyOnWriteArraySet();

    private final RateLimiter warningRateLimiter = RateLimiter.create(1.0 / 60);
    @GuardedBy("warningLock")
    private int countSinceLastWarning;

    TraceCollectorImpl(ExecutorService executorService, ConfigService configService,
            SnapshotRepository snapshotRepository, @Nullable Aggregator aggregator, Clock clock,
            Ticker ticker) {
        this.executorService = executorService;
        this.configService = configService;
        this.snapshotRepository = snapshotRepository;
        this.aggregator = aggregator;
        this.clock = clock;
        this.ticker = ticker;
    }

    public boolean shouldStore(Trace trace) {
        if (trace.isStuck() || trace.getError() != null) {
            return true;
        }
        long duration = trace.getDuration();
        // check if should store for user tracing
        String user = trace.getUser();
        if (user != null && user.equals(configService.getUserOverridesConfig().getUser())
                && duration >= MILLISECONDS.toNanos(configService.getUserOverridesConfig()
                        .getStoreThresholdMillis())) {
            return true;
        }
        // check if should store for fine profiling
        if (trace.isFine()) {
            int fineStoreThresholdMillis =
                    configService.getFineProfilingConfig().getStoreThresholdMillis();
            return trace.getDuration() >= MILLISECONDS.toNanos(fineStoreThresholdMillis);
        }
        // fall back to general store threshold
        long storeThresholdMillis = trace.getStoreThresholdMillisOverride();
        if (storeThresholdMillis == -1) {
            storeThresholdMillis = configService.getGeneralConfig().getStoreThresholdMillis();
        }
        return trace.getDuration() >= MILLISECONDS.toNanos(storeThresholdMillis);
    }

    public Collection<Trace> getPendingCompleteTraces() {
        return pendingCompleteTraces;
    }

    @Override
    public void onCompletedTrace(final Trace trace) {
        boolean store = shouldStore(trace);
        if (store) {
            if (pendingCompleteTraces.size() < PENDING_LIMIT) {
                pendingCompleteTraces.add(trace);
            } else {
                logPendingLimitWarning();
                store = false;
            }
        }
        // capture time is calculated by the aggregator because it depends on monotonically
        // increasing capture times so it can flush aggregates without concern for new data points
        // arriving with a prior capture time
        // this is a reasonable place to get the capture time since this code is still being
        // executed by the trace thread
        final long captureTime;
        if (aggregator == null) {
            captureTime = clock.currentTimeMillis();
        } else {
            // there's a small window where something bad could happen and the snapshot is not
            // stored, and aggregate stored_trace_count would be off by one
            captureTime = aggregator.add(trace.isBackground(), trace.getTransactionName(),
                    trace.getDuration(), store);
        }
        if (store) {
            // onCompleteAndShouldStore must be called by the trace thread
            trace.onCompleteAndShouldStore();
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Snapshot snapshot =
                                SnapshotCreator.createCompletedSnapshot(trace, captureTime);
                        snapshotRepository.store(snapshot);
                        pendingCompleteTraces.remove(trace);
                    } catch (Throwable t) {
                        // log and terminate successfully
                        logger.error(t.getMessage(), t);
                    }
                }
            });
        }
    }

    // no need to throttle stuck trace storage since throttling is handled upstream by using a
    // single thread executor in StuckTraceCollector
    @Override
    public void onStuckTrace(Trace trace) {
        try {
            Snapshot snaphsot = SnapshotCreator.createActiveSnapshot(trace,
                    clock.currentTimeMillis(), ticker.read(), false);
            if (!trace.isCompleted()) {
                snapshotRepository.store(snaphsot);
            }
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
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
}
