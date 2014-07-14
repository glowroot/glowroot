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

import com.google.common.collect.Sets;
import com.google.common.io.CharSource;
import com.google.common.util.concurrent.RateLimiter;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigService;
import org.glowroot.config.ProfilingConfig;
import org.glowroot.markers.GuardedBy;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.Singleton;
import org.glowroot.markers.UsedByReflection;
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

    // this is only used for benchmarking overhead of trace storage
    @OnlyUsedByTests
    @UsedByReflection
    private static boolean useSynchronousStore;

    private final ExecutorService executorService;
    private final ConfigService configService;
    private final SnapshotRepository snapshotRepository;
    @Nullable
    private final TransactionPointCollector transactionPointCollector;
    private final Clock clock;
    private final Ticker ticker;
    private final Set<Trace> pendingCompleteTraces = Sets.newCopyOnWriteArraySet();

    private final RateLimiter warningRateLimiter = RateLimiter.create(1.0 / 60);
    @GuardedBy("warningLock")
    private int countSinceLastWarning;

    TraceCollectorImpl(ExecutorService executorService, ConfigService configService,
            SnapshotRepository snapshotRepository,
            @Nullable TransactionPointCollector transactionPointCollector, Clock clock,
            Ticker ticker) {
        this.executorService = executorService;
        this.configService = configService;
        this.snapshotRepository = snapshotRepository;
        this.transactionPointCollector = transactionPointCollector;
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
        if (user != null && user.equalsIgnoreCase(configService.getUserTracingConfig().getUser())
                && duration >= MILLISECONDS.toNanos(configService.getUserTracingConfig()
                        .getStoreThresholdMillis())) {
            return true;
        }
        // check if should store for profiling
        if (trace.isProfiled()) {
            int profiledStoreThresholdMillis =
                    configService.getProfilingConfig().getStoreThresholdMillis();
            if (profiledStoreThresholdMillis != ProfilingConfig.USE_GENERAL_STORE_THRESHOLD
                    && trace.getDuration() >= MILLISECONDS.toNanos(profiledStoreThresholdMillis)) {
                return true;
            }
        }
        // check if trace-specific store threshold was set
        long traceStoreThresholdMillis = trace.getStoreThresholdMillisOverride();
        if (traceStoreThresholdMillis != Trace.USE_GENERAL_STORE_THRESHOLD
                && trace.getDuration() >= MILLISECONDS.toNanos(traceStoreThresholdMillis)) {
            return true;
        }
        // fall back to general store threshold
        long generalStoreThresholdMillis =
                configService.getGeneralConfig().getStoreThresholdMillis();
        return trace.getDuration() >= MILLISECONDS.toNanos(generalStoreThresholdMillis);
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
        // increasing capture times so it can flush transaction points without concern for new data
        // arriving with a prior capture time
        //
        // this is a reasonable place to get the capture time since this code is still being
        // executed by the trace thread
        final long captureTime;
        if (transactionPointCollector == null) {
            captureTime = clock.currentTimeMillis();
        } else {
            // there's a small window where something bad could happen and the snapshot is not
            // stored, and transaction point 'stored count' would be off by one
            captureTime = transactionPointCollector.add(trace, store);
        }
        if (store) {
            // onCompleteAndShouldStore must be called by the trace thread
            trace.onCompleteAndShouldStore();
            Runnable command = new Runnable() {
                @Override
                public void run() {
                    try {
                        Snapshot snapshot =
                                SnapshotCreator.createCompletedSnapshot(trace, captureTime);
                        store(snapshot, trace);
                        pendingCompleteTraces.remove(trace);
                    } catch (Throwable t) {
                        // log and terminate successfully
                        logger.error(t.getMessage(), t);
                    }
                }
            };
            if (useSynchronousStore) {
                command.run();
            } else {
                executorService.execute(command);
            }
        }
    }

    // no need to throttle stuck trace storage since throttling is handled upstream by using a
    // single thread executor in StuckTraceCollector
    @Override
    public void onStuckTrace(Trace trace) {
        try {
            Snapshot snaphsot = SnapshotCreator.createActiveSnapshot(trace,
                    clock.currentTimeMillis(), ticker.read());
            if (!trace.isCompleted()) {
                store(snaphsot, trace);
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

    private void store(Snapshot snapshot, Trace trace) throws IOException {
        Long captureTick = trace.getEndTick();
        if (captureTick == null) {
            captureTick = ticker.read();
        }
        CharSource spans = SpansCharSourceCreator
                .createSpansCharSource(trace.getSpansCopy(), trace.getStartTick(), captureTick);
        CharSource profile = ProfileCharSourceCreator.createProfileCharSource(trace.getProfile());
        CharSource outlierProfile =
                ProfileCharSourceCreator.createProfileCharSource(trace.getOutlierProfile());
        snapshotRepository.store(snapshot, spans, profile, outlierProfile);
    }
}
