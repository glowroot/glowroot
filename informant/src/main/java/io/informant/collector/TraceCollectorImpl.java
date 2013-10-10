/*
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
package io.informant.collector;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import checkers.lock.quals.GuardedBy;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.base.Ticker;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.Clock;
import io.informant.config.ConfigService;
import io.informant.config.GeneralConfig;
import io.informant.markers.Singleton;
import io.informant.trace.TraceCollector;
import io.informant.trace.model.Trace;

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
        if (trace.isStuck() || trace.isError()) {
            return true;
        }
        long duration = trace.getDuration();
        // check if should store for user tracing
        String userId = trace.getUserId();
        if (userId != null && userId.equals(configService.getUserOverridesConfig().getUserId())
                && duration >= MILLISECONDS.toNanos(configService.getUserOverridesConfig()
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
        // fall back to general store threshold
        return shouldStoreBasedOnGeneralStoreThreshold(trace);
    }

    public Collection<Trace> getPendingCompleteTraces() {
        return pendingCompleteTraces;
    }

    public void onCompletedTrace(final Trace trace) {
        // capture time is calculated by the aggregator because it depends on monotonically
        // increasing capture times so it can flush aggregates without concern for new data points
        // arriving with a prior capture time
        // this is a reasonable place to get the capture time since this code is still being
        // executed by the trace thread
        String grouping = Objects.firstNonNull(trace.getGrouping(), "<no grouping provided>");
        final long captureTime;
        if (aggregator == null) {
            captureTime = clock.currentTimeMillis();
        } else {
            captureTime = aggregator.add(grouping, trace.getDuration());
        }
        if (shouldStore(trace)) {
            // onCompleteAndShouldStore must be called by the trace thread
            trace.onCompleteAndShouldStore();
            if (pendingCompleteTraces.size() >= PENDING_LIMIT) {
                logPendingLimitWarning();
                return;
            }
            pendingCompleteTraces.add(trace);
            executorService.execute(new Runnable() {
                public void run() {
                    try {
                        Snapshot snapshot = SnapshotCreator.createCompletedSnapshot(trace,
                                captureTime);
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
    public void onStuckTrace(Trace trace) {
        try {
            Snapshot snaphsot = SnapshotCreator.createActiveSnapshot(trace, ticker.read(),
                    clock.currentTimeMillis(), false);
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

    private boolean shouldStoreBasedOnGeneralStoreThreshold(Trace trace) {
        int storeThresholdMillis = configService.getGeneralConfig().getStoreThresholdMillis();
        return storeThresholdMillis != GeneralConfig.STORE_THRESHOLD_DISABLED
                && trace.getDuration() >= MILLISECONDS.toNanos(storeThresholdMillis);
    }
}
